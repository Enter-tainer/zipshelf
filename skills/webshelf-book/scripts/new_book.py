#!/usr/bin/env python3
"""从模板生成一本空书的骨架：目录页 + N 个章节页（上下页链接已接好）+ 样式 + KaTeX + 封面。

    python new_book.py mybook --title "我的书" --subtitle "副标题" --chapters 3

生成后改正文、渲染公式、自检、打包：

    python check_book.py mybook
    python pack_book.py mybook -o mybook.zip
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATES = os.path.join(HERE, os.pardir, "templates")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", help="输出目录（不存在则创建）")
    ap.add_argument("--title", required=True)
    ap.add_argument("--subtitle", default="")
    ap.add_argument("--chapters", type=int, default=1)
    ap.add_argument("--lang", default="zh-Hans")
    args = ap.parse_args()

    if os.path.exists(args.out) and os.listdir(args.out):
        print("错误：%s 已存在且非空" % args.out, file=sys.stderr)
        return 2

    os.makedirs(args.out, exist_ok=True)
    shutil.copytree(os.path.join(TEMPLATES, "assets"), os.path.join(args.out, "assets"))

    title = args.title
    subtitle = args.subtitle

    # ---------- 目录页
    index = open(os.path.join(TEMPLATES, "index.html"), encoding="utf-8").read()
    index = index.replace("书名 · Book Title", title + (" · " + subtitle if subtitle else ""))
    index = index.replace("书名 — 一句话简介", title + " — 用 webshelf-book 生成")
    index = index.replace(">书名<", ">" + title + "<")

    toc = []
    for i in range(1, args.chapters + 1):
        toc.append(
            '  <li><a href="ch%04d.html"><span class="n">%d</span>'
            '<span class="t"><span class="tn-zh" lang="zh-CN">第 %d 章 标题</span>'
            '<span class="tn-en" lang="en">Chapter %d</span></span></a></li>'
            % (i, i, i, i)
        )
    index = re.sub(r'<ul class="toc">.*?</ul>',
                   '<ul class="toc">\n' + "\n".join(toc) + "\n</ul>",
                   index, flags=re.S)
    open(os.path.join(args.out, "index.html"), "w", encoding="utf-8").write(index)

    # ---------- 章节页
    page_tpl = open(os.path.join(TEMPLATES, "page.html"), encoding="utf-8").read()
    # 模板里那段解释上下页怎么接的注释，生成时不再需要
    page_tpl = re.sub(r"<!-- 顶栏：.*?-->", "", page_tpl, flags=re.S)
    for i in range(1, args.chapters + 1):
        page = page_tpl
        page = page.replace("<html lang=\"zh-Hans\">", '<html lang="%s">' % args.lang)
        page = page.replace("第 1 章 标题 · Chapter Title", "第 %d 章 标题 · Chapter %d" % (i, i))
        page = page.replace("第 1 章 标题", "第 %d 章 标题" % i)
        page = page.replace(">Chapter Title<", ">Chapter %d<" % i)
        page = page.replace("<span class=\"crumb\">第 1 章</span>",
                            "<span class=\"crumb\">第 %d 章</span>" % i)

        steps = ['  <a class="step" href="index.html">目录</a>',
                 '  <span class="crumb">第 %d 章</span>' % i]
        if i > 1:
            steps.append('  <a class="step" href="ch%04d.html" rel="prev">← 上一页</a>' % (i - 1))
        if i < args.chapters:
            steps.append('  <a class="step" href="ch%04d.html" rel="next">下一页 →</a>' % (i + 1))
        header = ('<header class="site-head"><div class="wrap">\n'
                  + "\n".join(steps) + "\n</div></header>")
        page = re.sub(r'<header class="site-head">.*?</header>', header, page, flags=re.S)

        open(os.path.join(args.out, "ch%04d.html" % i), "w", encoding="utf-8").write(page)

    print("生成 %s：%d 章" % (args.out, args.chapters))
    print("下一步：")
    print("  1. 改 %s/index.html 和 ch*.html 里的正文（占位符是「第 N 章 标题」）" % args.out)
    print("  2. 渲染公式：node scripts/render_math.mjs 'E=mc^2'，替换页面里的 <!-- ... --> 占位")
    print("  3. python scripts/check_book.py %s" % args.out)
    print("  4. python scripts/pack_book.py %s -o book.zip" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
