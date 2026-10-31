#!/usr/bin/env python3
"""把一本书的静态站目录打包成 zipshelf 可导入的 zip。

    python pack_book.py site/ -o book.zip

- 校验根目录有 index.html
- 默认全部 DEFLATE（不需要「无压缩 zip」，App 会零解压转发）
- 确定性打包：条目排序、固定时间戳，同样输入得到同样字节
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile

SKIP_DIRS = {".git", ".github", "node_modules", "__pycache__", ".idea", ".vscode"}
FIXED_TIME = (2026, 1, 1, 0, 0, 0)


def human(n: int) -> str:
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%.0f KB" % (n / 1024)
    return "%.2f MB" % (n / 1048576)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("site", help="静态站目录")
    ap.add_argument("-o", "--out", required=True, help="输出 zip 路径")
    ap.add_argument("--store-ext", default="",
                    help="这些扩展名不压缩，逗号分隔（一般用不到）")
    args = ap.parse_args()

    root = os.path.abspath(args.site)
    if not os.path.isfile(os.path.join(root, "index.html")):
        print("错误：%s 里没有 index.html —— 这不是一本可导入的书" % root, file=sys.stderr)
        return 2

    store_ext = {("." + e.strip().lstrip(".")).lower()
                 for e in args.store_ext.split(",") if e.strip()}

    files = []
    for cur, dirs, names in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS and not d.startswith("."))
        for n in sorted(names):
            if n.startswith("."):
                continue
            full = os.path.join(cur, n)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            files.append((rel, full))

    files.sort()
    raw = 0
    with zipfile.ZipFile(args.out, "w") as zf:
        for rel, full in files:
            ext = os.path.splitext(rel)[1].lower()
            method = zipfile.ZIP_STORED if ext in store_ext else zipfile.ZIP_DEFLATED
            info = zipfile.ZipInfo(rel, date_time=FIXED_TIME)
            info.compress_type = method
            info.external_attr = 0o644 << 16
            info.create_system = 3  # unix
            with open(full, "rb") as fh:
                data = fh.read()
            raw += len(data)
            zf.writestr(info, data)

    size = os.path.getsize(args.out)
    print("输出      %s" % args.out)
    print("条目      %d 个" % len(files))
    print("原始      %s" % human(raw))
    print("zip       %s  （%.0f%%）" % (human(size), size * 100 / max(raw, 1)))

    if len(files) > 3000:
        print("提示：文件数偏多，部分静态托管有上限", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
