# CSS 约定（templates/assets/style.css）

这份样式表是「书 → 网页」两层叠起来的：前半是 EPUB 时代的 `book.css`（阅读器负责字号、
两端对齐、连字符），后半是 `WEB LAYER`（网页必须自己决定字号、栏宽、颜色）。
墨水屏上真正起作用的是后半部分和几条硬规则。

**别自己重写**，直接复制 `templates/assets/style.css`。要改就改这几个变量：

```css
:root {
  --ink: #000;          /* 唯一墨色 */
  --paper: #fff;        /* 唯一底色 */
  --measure: 43rem;     /* 栏宽，约 70 个汉字 */
  --gutter: 1.15rem;    /* 左右留白 */
}
```

## 颜色：只有两种

- 全站只有 `#000` / `#fff`（源码里出现的 `#000000` / `#ffffff` 是同一件事）
- 没有灰字（次要信息靠**字号变小**表达，不靠变灰）
- 没有 `box-shadow`、`border-radius`、`opacity<1`、渐变、半透明叠加
  - 半透明在墨水屏上会渲染成抖动的灰噪点
  - 圆角的抗锯齿边缘也是一圈灰
- 链接用「黑色下划线 + hover 反相」来区分，不靠颜色

## 字号与栏宽

```css
body   { font-size: 1.0625rem; line-height: 1.62; }
.wrap  { max-width: var(--measure); margin: 0 auto; padding: 0 var(--gutter) 5rem; }
@media (max-width: 34rem) { :root { --gutter: 0.85rem; } body { font-size: 1.02rem; } }
```

- 用 `rem`/`em` 而不是 `px`：浏览器缩放才能在墨水屏上生效
- `43rem` 大约是 70 个汉字一行，这是长时间阅读比较舒服的密度
- 行高 1.62 对中文略宽松，对公式行也留了空间

## 双语排版

两份文本成对出现，中文在前：

```html
<p class="ltx_p">
  <span class="tn-zh" lang="zh-CN">中文段落</span>
  <span class="tn-en" lang="en">English paragraph</span>
</p>
```

- `.tn-zh` / `.tn-en` 默认是块级，上下叠放
- 标题里用 `.tn-zh-inline`（`h1`–`h4` 会把它改成块级）
- 英文比中文小一档（`td .tn-en` 是 `0.82em`），这是刻意的层级
- **不可破坏的不变量**：两者不共享像素、英文永远在后

## 公式

| 类 | 用途 | 关键规则 |
|---|---|---|
| `.mi` | 行内公式 | `display:inline-block`，字号由 `--sn` 微调，保证不撑破行 |
| `.md` | 行间公式 | `display:block`，居中，`overflow-x:auto` |
| `.eqblock` | 一块行间公式（外层） | `margin:0.85em 0` |
| `.eqtable` / `.eqcell` / `.eqno` | 带编号的公式表 | `table-layout:fixed`，编号列 `nowrap` |
| `.katex` | KaTeX 输出 | `font-size:1.21em`（默认值，显式写出） |
| `.katex-display` | KaTeX 的 display 包装 | `margin:0.35em 0`（间距由外层给） |
| `.katex-mathml` | KaTeX 的 MathML 副本 | **别删**，屏幕阅读器用 |

**宽公式的处理原则**：不缩小，让那一行横向滚动。

```css
.md > .katex-display { overflow-x: auto; }
```

缩到 60% 在墨水屏上就是不可读；滚动虽然要动手势，但字还是原大。
（实测这本书的 894 个独立行间公式，在 ≥700px 宽时没有一个超出栏宽。）

## 插图

```css
img.figimg { display:block; margin:0.6em auto; max-width:100%; height:auto; }
```

- 不放大（墨水屏 DPI 高，放大只会糊）
- SVG 优先；位图只用于本来就是位图的东西（照片等）
- 需要抖动/1-bit 的图在产出端就处理好，别指望浏览器

## 分页与断行（印刷/EPUB 残留，网页里保留无害）

```css
h1 + .ltx_para, h2 + .ltx_para { break-before: avoid; }  /* 标题不要孤零零在页底 */
```

## 什么时候值得改

- **字太小**：改 `--measure`（栏宽）或 `body font-size`，别改 `px`
- **公式太挤**：调 `.md` 的 `margin`，不是调 `katex` 的字号
- **想加侧栏/浮窗**：别用 `position:fixed`（墨水屏滚动重排会残影）
