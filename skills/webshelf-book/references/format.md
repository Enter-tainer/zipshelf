# zip 格式与 URL 契约

## zip 契约

| 项 | 要求 |
|---|---|
| 入口 | 根目录 `index.html` |
| 条目压缩 | STORE 或 DEFLATE 都行（App 两种都处理） |
| 文件名编码 | UTF-8（Python `zipfile` 写非 ASCII 名会自动置 UTF-8 标志） |
| 路径 | 不能有绝对路径、不能有 `..`；不使用符号链接 |
| 加密 | 不支持（检测到直接报错） |
| ZIP64 | 不支持（>4 GB 或 >65535 条目）；书不会这么大，检测到会明确报错 |
| 目录条目 | 可有可无（App 不依赖） |

打包用 `scripts/pack_book.py`；它默认全部 DEFLATE，并做入口校验。

### 为什么不需要「无压缩 zip」

zip 条目的 deflate 数据流，前面补 10 字节 gzip 头、后面补 8 字节尾，就是一条**合法 gzip 流**；
CRC32 和原始长度本来就存在 zip 中央目录里，不用读内容就能拼出来。App 于是这样服务：

```
读中央目录 → 取 (dataOffset, compressedSize, crc32, size)
          → 读那段 raw deflate
          → 拼 gzip 头 + 那段字节 + gzip 尾
          → HTTP 200 + Content-Encoding: gzip
```

App 侧零解压、零整包读入，浏览器自己解。实测一本 41.31 MB（原始）的书：zip 5.95 MB，
线上传输 5.94 MB，解码后哈希与 zip 内逐字节一致。

如果改成全 STORED，zip 会变成 41.3 MB（7 倍），换不到任何收益。

## URL 布局

单端口（默认 8787，占用则往后找 24 个），所有书共享：

```
GET /                       书架列表页（所有书的 id 和标题）
GET /healthz                探活
GET /books/{id}/            该书 index.html
GET /books/{id}/{path}      该书内某个条目
```

`{id}` 是 App 导入时生成并持久化的 UUID（对应 `filesDir/books/{id}/`），与 zip 内容无关。

### 为什么必须相对路径

书挂在**子路径**下。`/assets/style.css` 在 HTTP 里是「从 origin 根开始」的绝对路径，
浏览器会请求 `http://127.0.0.1:8787/assets/style.css`——那里不属于任何一本书，所以 404。

- 可以：`assets/style.css`、`./x`、`../x`（不越出书根）、`#anchor`、`?q=1`
- 不可以：`/assets/style.css`、`//host/x`、`<base href="/">`
- 完整外链 `https://…` 路由没问题，但**离线读不了**

App 唯一的例外：书里的 `404.html` 里以 `/` 开头的路径，会在返回该错误页时被重写成
`/books/{id}/…`（这个文件是我们的服务器自己生成的，改它是安全的）。

### 其他行为

| 请求 | App 的行为 |
|---|---|
| `..` 或 `%2e%2e` | 400（防止越出该书） |
| 条目不存在 | 返回书的 `404.html`（改写过前缀）；没有则纯文本 404 |
| `Range: bytes=a-b` | 支持（只对 STORE 条目 / identity 响应）；对 gzip 响应退化为 200 全量 |
| `If-None-Match` | 支持，命中返回 304 |
| 缓存 | `Cache-Control: immutable` + `ETag: "<crc>-<size>"` |
| keep-alive | 支持，单连接最多 400 请求，空闲 60 s 回收 |

## 元信息

| 信息 | 来源 |
|---|---|
| 书架标题 | `index.html` 的 `<title>`；取不到用 zip 文件名 |
| 封面 | 根目录 `cover.png`/`jpg`/`jpeg`/`webp` > `<meta property="og:image">` > App 生成 1-bit 标题卡 |
| 文件数 | zip 条目数 |
| 大小 | zip 字节数 |

可选 `manifest.json` 目前不读，但留着无害。
