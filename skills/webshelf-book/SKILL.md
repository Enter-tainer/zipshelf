---
name: webshelf-book
description: 把一份文档做成墨水屏可读的黑白 HTML「书」，并打包成 zipshelf App 可导入的 zip。适用于：制作电子墨水屏阅读的黑白网页书、把 LaTeX/Markdown/EPUB 派生内容转成静态站、公式必须清晰且可选中（KaTeX 构建期渲染、无运行时 JS）、或要复刻现有两本书的版式。Triggers: 墨水屏书, 黑白 HTML 书, zipshelf, epub 公式看不清, 把书做成网页 zip, e-ink html book.
---

# 墨水屏黑板书（zipshelf 格式）

产出**一个 zip**，里面是一个纯静态、纯黑白、无 JS 的网站；用户把它导入 zipshelf App，
App 在本机起 HTTP 服务，用 EinkBro 打开阅读。

`templates/` 里放的就是现在在用的那份样式和页面骨架，直接抄就能得到一样的观感。

## 产物结构

```
book.zip
  index.html              标题页 + 摘要 + 目录
  ch0001.html … chNNNN.html   一章（或一节）一页
  colophon.html           可选：关于本版
  404.html                可选
  cover.png               可选：竖版黑白封面
  assets/
    style.css             ← 原样复制 templates/assets/style.css
    katex/katex.min.css   ← 复制 templates/assets/katex/
    katex/fonts/*.woff2
    images/               ← 插图（SVG 优先）
```

## 五条硬契约

违反其中任何一条，在墨水屏上就是「看起来坏」，而且 App 侧救不回来。

1. **入口是根目录的 `index.html`。**（整站被包进一层目录时 App 会自动下钻，但别依赖。）
2. **站内引用必须是相对路径。** 书被挂在 `/books/{id}/` 子路径下，`/assets/x.css` 这种
   根相对路径会解析到服务器根、直接 404。同时禁用 `<base href="/">` 和 `//host/x`。
3. **只有 `#000` 和 `#fff`。** 不要灰字、阴影、渐变、圆角、半透明（墨水屏上中间色 = 噪点）。
4. **无运行时 JS、无 CDN、无远程字体。** 断网必须能读。
5. **规模控制。** 文件数几十到几百；单页 ≤ 4 MB 左右。一章一页，不要把整本书塞一页。

## 公式：构建期渲染成真实文本

用 KaTeX 在**构建期**把 TeX 渲染成 HTML（`<span class="katex">`）。不要运行时 MathJax，
也不要把每个公式存成图片。

一本 5,331 个公式的书实测：

| 方案 | 文件数 | zip |
|---|---|---|
| 每个公式一张 SVG | 5,471 | 17.7 MB |
| **构建期 KaTeX** | **159** | **5.95 MB** |

渲染单个公式（需要 `npm i katex`）：

```bash
node scripts/render_math.mjs 'a^2+b^2=c^2'            # 行内
node scripts/render_math.mjs --display '\int_0^1 f(x)\,dx'   # 行间
```

输出直接粘进页面。TeX 源码从哪来都行（LaTeX 源、Markdown 的 `$...$`、EPUB 里公式图的 `alt` 文本）。

## 页面骨架

最快的方式是用脚手架生成一本空书（目录、上下页链接、样式、KaTeX、封面都接好）：

```bash
python scripts/new_book.py mybook --title "我的书" --subtitle "副标题" --chapters 3
```

然后只改正文。手写也行，照抄 `templates/page.html`（章节页）和 `templates/index.html`（目录页）。要点：

- `head` 里必须有 `charset`、`<title>`（App 用它做书架标题）、`assets/style.css`、
  `viewport`、`color-scheme: light`、`assets/katex/katex.min.css`
- 顶栏 `header.site-head`：目录 + 上一页/下一页，**相对链接**
- 正文 `section.ltx_chapter` + `h1.ltx_title_chapter`
- 双语：`<span class="tn-zh" lang="zh-CN">…</span><span class="tn-en" lang="en">…</span>`
  成对出现，**中文在前、英文在后**
- 行内公式包 `<span class="mi">`；行间公式包 `<span class="md">`（或 `.eqblock` + `.eqtable`）

## 排版

直接用 `templates/assets/style.css`——就是这两本书在用的 web layer，别自己另写一套。

正文字体写的是 `"Zipshelf Serif"` + `font-weight: 500`：这是**系统级**字体（思源宋 Medium，
由 Magisk 模块装进系统），书里不放字体文件、不用 `@font-face`。没装模块时按字体栈回退到
系统自带的思源宋/宋体，`font-weight: 500` 不生效但排版不会坏。
关键数值：

| 变量 / 规则 | 值 | 说明 |
|---|---|---|
| `--measure` | `43rem` | 阅读栏宽，约 70 个汉字 |
| `--gutter` | `1.15rem` | 左右留白（`34rem` 以下变 `0.85rem`） |
| body `font-size` | `1.0625rem` | 相对单位，让浏览器缩放仍然有效 |
| body `line-height` | `1.62` | |
| 链接 | 黑字 + 1px 黑色下划线；hover 反相（黑底白字） | |
| 插图 | `max-width:100%; height:auto` | 不放大、不裁切 |
| 宽公式 | 该行 `overflow-x:auto` 横向滚动，**不缩小** | 缩到 60% 就看不清了 |
| KaTeX 字号 | `1.21em`（KaTeX 默认，显式写出） | |

细节与选择器见 `references/css.md`。

## 封面

可选。`cover.png` 放根目录，竖版（约 1200×1600）、纯黑白。
没封面时 App 会自动生成一张 1-bit 标题卡，所以不做也能用。
（`og:image` 也会被读，但那是横图，不适合书封。）

## 打包

```bash
python scripts/pack_book.py site/ -o book.zip
```

默认全部 DEFLATE。**不需要**「无压缩 zip」：App 会把 zip 里的 deflate 流原样包成 gzip
转发给浏览器，App 侧零解压，6 MB 的书线上传输就是 6 MB。

## 自检（交付前必跑）

```bash
python scripts/check_book.py site/         # 或 book.zip
```

检查项：根 `index.html`、绝对路径引用、非黑白颜色、缺失的本地引用、外链数量、体积分布。
**退出码 0 才算可交付**；有 FAIL 就按提示改。

## 真机验证

```bash
# 推书进 App 的 inbox（App 里也可以点「导入 zip」）
adb shell run-as moe.mgt.zipshelf mkdir -p files/inbox
adb push book.zip /data/local/tmp/
adb shell run-as moe.mgt.zipshelf cp /data/local/tmp/book.zip files/inbox/

adb forward tcp:8899 tcp:8787
curl -s http://127.0.0.1:8899/healthz
curl -s -D - -o NUL http://127.0.0.1:8899/books/<id>/index.html
```

## 常见失败模式

| 症状 | 原因 | 修法 |
|---|---|---|
| 页面能开，样式全丢 | CSS 用了根相对路径 | 改成 `assets/style.css` |
| 图片裂开 / 公式空白 | 引用路径大小写不符，或用了 `<base href="/">` | 检查引用；去掉 `<base>` |
| 字发灰、图有阴影 | CSS 里有灰色、`box-shadow`、`border-radius`、半透明 | 跑 `check_book.py` 扫出来 |
| 整本书一页，翻起来卡 | 单页太大（>4 MB） | 按章/节拆页 |
| 导入报「找不到 index.html」 | zip 里多套了一层目录且不是唯一目录 | 让 `index.html` 位于 zip 根 |
| 公式糊 | 用了图片公式 | 改构建期 KaTeX |
| 打开白屏 | 用了 CDN 脚本且断网 | 去掉一切远程依赖 |

## 与 App 的契约

- `GET /books/{id}/{path}`，`{id}` 是 App 内部 UUID；你不需要关心
- 缺文件时返回书里的 `404.html`，其中以 `/` 开头的路径会被 App 改写成 `/books/{id}/…`
- 只支持 STORE / DEFLATE 条目，遇到 ZIP64 直接报错
- 路径里的 `..` 会被拒绝（400），书不能越出自己那本书

更多：`references/format.md`
