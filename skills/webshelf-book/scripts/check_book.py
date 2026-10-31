#!/usr/bin/env python3
"""校验一本 zipshelf 书（目录或 zip 都可以）。

    python check_book.py site/
    python check_book.py book.zip

退出码 0 = 可交付；1 = 有 FAIL。
"""
from __future__ import annotations

import argparse
import os
import posixpath
import re
import sys
import zipfile
import zlib
from collections import Counter, defaultdict
from urllib.parse import unquote

TEXT_EXT = {".html", ".htm", ".xhtml", ".css", ".js", ".svg"}

# 会坏掉的绝对引用
ABS_REF = [
    ("href=\"/", re.compile(rb'href\s*=\s*["\']/')),
    ("src=\"/", re.compile(rb'src\s*=\s*["\']/')),
    ("action=\"/", re.compile(rb'action\s*=\s*["\']/')),
    ("url(/", re.compile(rb'url\(\s*["\']?/')),
    ("<base>", re.compile(rb'<base\b', re.I)),
    ("协议相对 //", re.compile(rb'(?:href|src)\s*=\s*["\']//')),
]

HEX = re.compile(rb"#[0-9a-fA-F]{3,8}\b")
OK_COLORS = {"#000", "#fff", "#000000", "#ffffff"}
OTHER_COLOR_FN = re.compile(rb"\b(?:rgba?|hsla?)\s*\(")
NAMED_GRAY = re.compile(
    rb"\b(?:gray|grey|silver|lightgray|lightgrey|darkgray|darkgrey|"
    rb"gainsboro|whitesmoke|dimgray|dimgrey)\b", re.I)

ATTR = re.compile(rb'(?:href|src)\s*=\s*(?:"([^"]*)"|\'([^\']*)\')')
SKIP_SCHEMES = ("http://", "https://", "mailto:", "data:", "javascript:", "tel:", "//")


class DirSource:
    def __init__(self, root: str):
        self.root = root
        self._names = []
        for cur, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            for f in files:
                if f.startswith("."):
                    continue
                rel = os.path.relpath(os.path.join(cur, f), root)
                self._names.append(rel.replace(os.sep, "/"))

    def names(self):
        return self._names

    def read(self, name: str) -> bytes:
        with open(os.path.join(self.root, name.replace("/", os.sep)), "rb") as fh:
            return fh.read()


class ZipSource:
    def __init__(self, path: str):
        self.zf = zipfile.ZipFile(path)
        self._names = [i.filename for i in self.zf.infolist() if not i.is_dir()]

    def names(self):
        return self._names

    def read(self, name: str) -> bytes:
        return self.zf.read(name)


def human(n: int) -> str:
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%.0f KB" % (n / 1024)
    return "%.2f MB" % (n / 1048576)


def strip_html_comments(data: bytes) -> bytes:
    """去掉 HTML 注释：注释里示例性的链接不该被当成真实引用。"""
    return re.sub(rb"<!--.*?-->", b"", data, flags=re.S)


def strip_css_comments(data: bytes) -> bytes:
    """去掉 CSS 注释：注释里的 'no grey text' 之类不该被当成灰色声明。"""
    return re.sub(rb"/\*.*?\*/", b"", data, flags=re.S)


def style_context(data: bytes) -> bytes:
    """只取 HTML 里的 style="..." 和 <style>...</style> 内容，避免把正文散文当成颜色声明。"""
    parts = [m.group(1) for m in re.finditer(rb'style\s*=\s*"([^"]*)"', data, re.I)]
    parts += [m.group(1) for m in re.finditer(rb"style\s*=\s*'([^']*)'", data, re.I)]
    parts += [m.group(1) for m in
              re.finditer(rb"<style\b[^>]*>(.*?)</style>", data, re.I | re.S)]
    return b"\n".join(parts)


def resolve(base_dir: str, ref: str) -> str:
    ref = ref.split("#", 1)[0].split("?", 1)[0]
    ref = unquote(ref)
    if ref.startswith("/"):
        return "\0abs" + ref
    return posixpath.normpath(posixpath.join(base_dir, ref) if base_dir else ref)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("book")
    ap.add_argument("--max-page-mb", type=float, default=4.0)
    ap.add_argument("--max-files", type=int, default=3000)
    args = ap.parse_args()

    src = ZipSource(args.book) if args.book.lower().endswith(".zip") else DirSource(args.book)
    names = src.names()
    name_set = set(names)
    text_files = [n for n in names if os.path.splitext(n)[1].lower() in TEXT_EXT]

    fails, warns = [], []
    print("检查 %s" % args.book)
    print("  文件 %d 个，文本条目 %d 个\n" % (len(names), len(text_files)))

    # ---- 1. 入口
    print("[入口]")
    if "index.html" in name_set:
        print("  ✔ 根目录有 index.html")
    else:
        top = {n.split("/")[0] for n in names if "/" in n}
        if len(top) == 1 and (list(top)[0] + "/index.html") in name_set:
            warns.append("index.html 在一层目录下（App 会自动下钻，但建议放到根）")
            print("  ⚠ index.html 不在根目录，而在 %s/ 下" % list(top)[0])
        else:
            fails.append("缺少根目录 index.html")
            print("  ✘ 找不到根目录 index.html —— 这不是一本可导入的书")

    # ---- 2. 绝对路径（404.html 例外：App 会把这个错误页里的根相对路径重写掉）
    print("\n[绝对路径]")
    abs_hits = defaultdict(list)
    for n in text_files:
        data = src.read(n)
        for label, rx in ABS_REF:
            c = len(rx.findall(data))
            if c:
                abs_hits[label].append((n, c))
    exempt = defaultdict(list)
    for label, items in list(abs_hits.items()):
        keep = []
        for n, c in items:
            if posixpath.basename(n) == "404.html":
                exempt[label].append((n, c))
            else:
                keep.append((n, c))
        if keep:
            abs_hits[label] = keep
        else:
            del abs_hits[label]
    if abs_hits:
        for label, items in abs_hits.items():
            total = sum(c for _, c in items)
            fails.append("%s：%d 处" % (label, total))
            print("  ✘ %-14s %d 处，文件：%s" % (label, total,
                  ", ".join("%s(%d)" % (f, c) for f, c in items[:4])))
        print("     → 改成相对路径；书挂在 /books/{id}/ 子路径下，根相对会 404")
    else:
        print("  ✔ 没有根相对路径 / <base> / 协议相对引用")
    if exempt:
        total = sum(c for items in exempt.values() for _, c in items)
        warns.append("404.html 里有 %d 处根相对路径（App 会改写，可接受）" % total)
        print("  ⚠ 404.html 里有 %d 处根相对路径——App 会按书前缀改写，可以接受" % total)

    # ---- 3. 颜色（只扫样式上下文，不扫正文；插图颜色单独降级为提醒）
    print("\n[颜色]")
    css_bad, inline_bad, svg_bad = Counter(), Counter(), Counter()
    css_gray, inline_gray, color_fns = Counter(), Counter(), Counter()
    for n in text_files:
        ext = os.path.splitext(n)[1].lower()
        data = src.read(n)
        if ext == ".css":
            scan, hex_target, gray_target = strip_css_comments(data), css_bad, css_gray
        elif ext == ".svg":
            scan, hex_target, gray_target = data, svg_bad, Counter()
        elif ext in (".html", ".htm", ".xhtml"):
            scan = style_context(data)
            hex_target, gray_target = inline_bad, inline_gray
        else:
            continue
        for m in HEX.findall(scan):
            c = m.decode().lower()
            if c not in OK_COLORS:
                hex_target[c] += 1
        for m in NAMED_GRAY.findall(scan):
            gray_target[m.decode().lower()] += 1
        if hex_target is not svg_bad:
            for m in OTHER_COLOR_FN.finditer(scan):
                # 取整条声明片段，便于定位
                frag = scan[m.start():m.start() + 40].split(b")")[0].decode("utf-8", "replace")
                color_fns[frag] += 1

    if css_bad:
        fails.append("样式表里有 %d 种非黑白颜色" % len(css_bad))
        print("  ✘ style.css 非黑白色：%s" % ", ".join(
            "%s×%d" % (k, v) for k, v in css_bad.most_common(6)))
    if inline_bad:
        fails.append("行内样式里有 %d 种非黑白颜色" % len(inline_bad))
        print("  ✘ 行内 style/<style> 非黑白色：%s" % ", ".join(
            "%s×%d" % (k, v) for k, v in inline_bad.most_common(6)))
    if css_gray or inline_gray:
        fails.append("样式里出现灰色关键字")
        print("  ✘ 灰色关键字：%s" % ", ".join(
            list(css_gray) + list(inline_gray)))
    if color_fns:
        warns.append("样式里出现 rgb()/rgba()/hsl()：可能是半透明灰")
        print("  ⚠ 非纯黑白的颜色函数 %d 处：" % sum(color_fns.values()))
        for frag, cnt in color_fns.most_common(3):
            print("      %s…  ×%d" % (frag, cnt))
        print("     → rgba(0,0,0,0.3) 之类在半透明叠加后就是灰；要绝对纯黑白就改成 #000")
    if not (css_bad or inline_bad or css_gray or inline_gray):
        print("  ✔ 样式表 / 行内样式只有 #000 / #fff")
    if svg_bad:
        warns.append("插图（SVG）含 %d 种彩色" % len(svg_bad))
        print("  ⚠ 插图有 %d 种彩色（%s …）" % (len(svg_bad), ", ".join(
            list(svg_bad)[:4])))
        print("     → 单色墨水屏上会渲染成灰度/抖动；要纯黑白的图，"
              "在产出端把矢量图转成灰度或 1-bit 位图")

    # ---- 4. 本地链接
    print("\n[本地引用]")
    missing = defaultdict(list)
    external = 0
    for n in text_files:
        if not n.lower().endswith((".html", ".htm", ".xhtml")):
            continue
        base_dir = posixpath.dirname(n)
        for m in ATTR.finditer(strip_html_comments(src.read(n))):
            ref = (m.group(1) or m.group(2) or b"").decode("utf-8", "replace").strip()
            if not ref or ref.startswith("#") or ref.lower().startswith(SKIP_SCHEMES):
                if ref.lower().startswith(("http://", "https://")):
                    external += 1
                continue
            target = resolve(base_dir, ref)
            if target.startswith("\0abs"):
                continue  # 已在 [绝对路径] 里报过
            if target.startswith(".."):
                warns.append("%s 引用了书外路径 %s" % (n, ref))
                missing["(书外)"].append((n, ref))
            elif target not in name_set:
                missing["缺失"].append((n, ref))
    if missing["缺失"]:
        fails.append("缺失本地引用 %d 处" % len(missing["缺失"]))
        print("  ✘ 指向不存在的文件 %d 处：" % len(missing["缺失"]))
        for f, r in missing["缺失"][:6]:
            print("      %s → %s" % (f, r))
    if missing["(书外)"]:
        print("  ⚠ 指向书外 %d 处" % len(missing["(书外)"]))
    if not missing["缺失"]:
        print("  ✔ 本地引用都能解析")
    if external:
        print("  ⚠ 外链 %d 处（http/https）—— 路由没问题，但断网时读不了" % external)

    # ---- 5. 体积
    print("\n[体积]")
    raw = 0
    packed = 0
    per_ext = defaultdict(lambda: [0, 0])
    biggest = []
    for n in names:
        data = src.read(n)
        size = len(data)
        raw += size
        packed += len(zlib.compress(data, 6))
        ext = os.path.splitext(n)[1].lower() or "(无扩展名)"
        per_ext[ext][0] += 1
        per_ext[ext][1] += size
        biggest.append((size, n))
    print("  %-9s %5s %10s" % ("类型", "数量", "原始大小"))
    for ext, (cnt, size) in sorted(per_ext.items(), key=lambda kv: -kv[1][1])[:8]:
        print("  %-9s %5d %10s" % (ext, cnt, human(size)))
    print("  ────────────────────────")
    print("  合计      %5d %10s   （预估 zip 约 %s）" % (len(names), human(raw), human(packed)))

    if len(names) > args.max_files:
        warns.append("文件数 %d 偏多" % len(names))
    biggest.sort(reverse=True)
    over = [(s, n) for s, n in biggest if s > args.max_page_mb * 1048576]
    if over:
        warns.append("单文件超过 %.0f MB" % args.max_page_mb)
        print("\n  ⚠ 单文件超过 %.0f MB（墨水屏上打开会慢）：" % args.max_page_mb)
        for s, n in over[:4]:
            print("      %s  %s" % (human(s), n))

    # ---- 总结
    print("\n" + "=" * 46)
    if fails:
        print("结果：不可交付（%d 项 FAIL，%d 项提醒）" % (len(fails), len(warns)))
        for f in fails:
            print("  ✘ " + f)
        return 1
    print("结果：通过（%d 项提醒）" % len(warns))
    for w in warns:
        print("  ⚠ " + w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
