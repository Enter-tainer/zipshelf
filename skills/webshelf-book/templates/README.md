# 模板说明

这四个东西合起来就是一本能直接导入 zipshelf 的书：

```
site/
  index.html          ← templates/index.html（改标题和目录条目）
  ch0001.html …       ← templates/page.html（每章复制一份）
  404.html            ← 可选
  cover.png           ← 可选，竖版约 1200×1600，纯黑白
  assets/
    style.css         ← templates/assets/style.css 原样复制，不要改
    katex/            ← templates/assets/katex/ 原样复制（含 fonts/）
    images/           ← 你自己的插图
```

## 最小上手

```bash
# 一条命令生成骨架：目录页 + N 个章节页（上下页已接好）+ 样式 + KaTeX + 封面
python scripts/new_book.py mybook --title "我的书" --subtitle "副标题" --chapters 3
```

或者手工组装：

```bash
cp -r templates/assets site/
cp templates/index.html site/index.html
cp templates/page.html site/ch0001.html
# 改 site/index.html 的 <title> 和 <ul class="toc"> 条目
# 改 site/ch0001.html 的正文章节
python scripts/check_book.py site/
python scripts/pack_book.py site/ -o book.zip
```

## 渲染公式

```bash
npm i katex      # 一次性
node scripts/render_math.mjs 'a^2+b^2=c^2'                    # 行内 → 粘进 <span class="mi">
node scripts/render_math.mjs --display '\sum_{i=1}^n x_i'     # 行间 → 粘进 <span class="md">
```

## 为什么 style.css 不用改

它是这两本书实际在用的那一份：`--ink` / `--paper` / `--measure: 43rem` / `line-height: 1.62`，
以及 KaTeX 的尺寸、宽公式横向滚动、双语层级都已经调好了。
要调整阅读密度就改 `:root` 里那四个变量，别改具体规则。

## 注意

- `assets/katex/` 里的字体是 KaTeX 的（MIT 许可），必须整份带；`katex.min.css` 用相对路径
  `fonts/xxx.woff2` 引用它们，所以两者要放在同一层目录。
- 站内所有引用必须是**相对路径**；`/assets/...` 会被 zipshelf 当成服务器根，直接 404。
- 不要引入任何 CDN、远程字体或运行时 JS。
