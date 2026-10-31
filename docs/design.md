# 设计文档

目标：让「带公式的书」在电子墨水屏上读得舒服。绕开 EPUB 的公式短板，改用
**静态 HTML 站 + 本地 HTTP 容器**的交付形态。

- 产出端：把书渲染成纯静态、纯黑白的网站，打包成 zip（规范见 `skills/webshelf-book/`）
- 容器端（本仓库 `android/`）：导入 zip、书架、本机 serve、用 EinkBro 打开

## 职责边界

**做**：导入/存储/按需读取、本地 HTTP server、书架、前台保活、拉起浏览器。

**不做**：网站生成、公式渲染、排版 CSS（产出端的事）；阅读进度/滚动位置（浏览器的状态，
App 打开 URL 就结束）；EPUB/PDF 兼容；iOS。

## 实测基线

一本真实的书（30 页、159 个文件）：

| 类型 | 个数 | 原始 | zip 内 | 压缩率 |
|---|---:|---:|---:|---:|
| `.html` | 30 | 28.19 MB | 1.66 MB | 6% |
| `.svg` | 89 | 9.81 MB | 1.49 MB | 15% |
| `.png` | 17 | 3.00 MB | 2.53 MB | 84% |
| `.woff2` | 20 | 0.25 MB | 0.25 MB | 100% |
| `.css` | 2 | 0.06 MB | 0.01 MB | 23% |
| **合计** | **159** | **41.31 MB** | **5.93 MB** | **14.4%** |

两条推论：全站体积 93% 是 HTML，而 HTML 能压到 6%（KaTeX 的 `<span>` 高度重复）；
所以「无压缩 zip」会把 6 MB 的书变成 41.3 MB，7 倍，换不到东西。

## 决策

### D1 zip 保持 DEFLATE，App 侧零解压

zip 条目的 raw deflate 流，前面补 10 字节 gzip 头、后面补 8 字节尾，就是一条**合规 gzip 流**；
CRC32 与原始长度本来就在 zip 中央目录里，不需要读内容就能拼出来。

```
读中央目录 → (dataOffset, compressedSize, crc32, size)
         → FileChannel 读那段 raw deflate
         → gzip 头 + 那段字节 + gzip 尾
         → HTTP 200 + Content-Encoding: gzip
```

实测：`ch0006.html` 3,748,300 B → 线上 212,122 B，浏览器解压后 sha256 与 zip 内一致。

| 方案 | 单本 | App CPU | 结论 |
|---|---:|---|---|
| 全 STORED | 41.3 MB | 0 | ✗ |
| 全 DEFLATE + App 解压 | 5.95 MB | 每页 inflate 3.5 MB | 可行，兜底 |
| **DEFLATE + gzip 透明转发** | **5.95 MB** | **0** | ✓ |
| 解压到私有目录 | 41.3 MB×2 | 0 | 只用于调试 |

补充：`stored` + 4 字节对齐只在用 `MappedByteBuffer` 时才是硬要求；`FileChannel.transferTo`
和普通 `read` 都不需要对齐。本实现完全不用 mmap。

兜底：客户端不带 `Accept-Encoding: gzip` 时退回流式 inflate 发 identity。

### D2 zip 契约

- 入口：根目录 `index.html`（整站包在唯一顶层目录里时自动下钻）
- 标题：`<title>`，取不到用 zip 文件名
- 封面：`cover.png|jpg|webp` > `og:image` > **App 生成的 1-bit 标题卡**
- 条目可以是 STORE 或 DEFLATE；UTF-8 文件名；不允许绝对路径 / `..` / 加密 / ZIP64
- 可选 `manifest.json`（目前不读，留着无害）

### D3 读取层

自己解析 EOCD + 中央目录（`java.util.zip.ZipFile` 不暴露 data offset），内存里只留中央目录
（159 条 ≈ 7 KB），内容按需从文件偏移读。每次读开自己的 `RandomAccessFile`，因此**无锁**。

### D4 HTTP

单端口 + `/books/{id}/` 前缀路由；GET/HEAD；keep-alive；`ETag: "<crc>-<size>"`；
`Cache-Control: immutable`；路径归一化拒绝 `..`（400）；缺文件返回书里的 `404.html`，
并把其中以 `/` 开头的路径重写成 `/books/{id}/…`；支持单段 Range（STORE / identity 响应）。

### D5 生命周期

前台服务（`dataSync`）承载 server，通知栏可停。端口 8787 起探测 24 个。
`targetSdk 34`：Android 15 起 `dataSync` 类型的前台服务有 6 小时/24 小时上限，侧载自用先规避。

服务被系统单独重启时，`onStartCommand` 会自己从磁盘恢复书单——否则会出现「服务活着但每本书都 404」的僵死状态。

### D6 书架

`filesDir/books/<uuid>/{book.zip, meta.properties}`；SAF 多选导入、流式 copy；
黑白卡片（3:4 封面 + 标题 + 大小/文件数）；长按打开/重命名/复制链接/删除。

### D7 墨水屏 UI 硬规则

| 规则 | 原因 |
|---|---|
| 只有 `#000` / `#fff` | 中间色 = 抖动噪点 |
| `colorControlHighlight` 透明 | 涟漪是半透明叠加 = 灰 |
| 按钮用 `TextView` + 自绘 shape | Material `Button` 自带灰底、阴影、全大写 |
| 对话框独立主题：白底黑框、无圆角、**关掉背景压暗** | 圆角抗锯齿出灰边；dim 会整屏蒙灰 |
| 列表分隔线 2dp 纯黑、关滚动条、关动画 | 默认分隔线太淡，滚动条是灰的 |
| 生成的封面阈值化成 1-bit + 关位图滤波 | 缩放插值会在边缘产生灰 |

实测（同一屏截图的灰阶像素占比）：改版前 4.26%（其中 16.5 万像素是按钮灰底），
改版后 **0.50%**，剩余全部来自文字抗锯齿；封面区 **0.0%**；对话框 2.71% → 0.67%。

### D9 —— 正文字体：装在系统里，而不是打进书里

目标：正文用思源宋 **Medium**（比 Regular 略粗一点）。实现方式是把完整可变字体装进
系统（Magisk 模块注册字体族 `Zipshelf Serif`，字重 400/500/600/700 用 `<axis tag="wght">`
指向同一份文件），书里的 CSS 只写族名 + `font-weight: 500`。

被否掉的三个方案：

| 方案 | 问题 |
|---|---|
| 每本书带一份子集字体 | 每本背一份（几百 KB~2 MB），换书就得重做，缺字会露出回退 |
| 每本书带完整字体 | 每本 +10~24 MB，zip 从 6 MB 涨到 20 MB+ |
| App 提供 `/@/fonts` + `@font-face` | 依赖 App 注入的全局 CSS，换浏览器/换环境就没了；APK 也要 +24 MB |

系统方案的代价：需要 root；`fonts.xml` 是整文件覆盖，ROM 升级后要重新生成。
详见 `docs/font.md`。

## 验证记录

| 项目 | 结果 |
|---|---|
| 单测 | 22 项全过 |
| 全站资源逐个比对 | **159/159 sha256 与 zip 内一致**，41.31 MB → 线上 5.94 MB（14.4%） |
| 大页 | `ch0006.html` 3.75 MB → 212 KB，哈希一致 |
| keep-alive | 单连接 159 连发通过（1.37 s） |
| 并发 | 16 连接 × 3 请求，0 错误，p50 44 ms / p95 59 ms |
| 多书隔离 | 同名路径不串、跨书请求 404、交错 20 次 0 串台 |
| 边界 | 路径穿越 400、未知书 404、gzip 条目 Range 退化为 200 |
| 真机渲染 | EinkBro 打开章节，正文与 KaTeX 公式文本可见 |

## 已知限制

1. 服务被系统重启后的自愈：已实现，未实测（无 root，无法制造真实重启）
2. HTTP 硬化：8 线程池、队列无界、慢客户端阻塞 `write` 时不受 60 s 读超时保护
3. 书 id 是 UUID：URL 长，删了重导链接失效 → 可改稳定 slug
4. 同机其他 App 可访问端口内容 → 可加 URL token
5. 产出端现状：插图（SVG）仍是彩色的，在单色墨水屏上会渲染成灰度/抖动；
   样式里还有一处 `rgba(0,0,0,0.3)` 的框线是灰的 —— 都已在 `check_book.py` 里标为提醒
