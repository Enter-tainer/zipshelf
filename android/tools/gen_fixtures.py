"""生成 einkapp 的 zip 测试夹具。

四种变体覆盖真实场景：
  fixture_deflate.zip  全 DEFLATE，根目录有 index.html（最接近真实的书）
  fixture_store.zip    全 STORE，用来验证 Range
  fixture_nested.zip   整站包在单层目录 book/ 里
  fixture_noidx.zip    没有 index.html，应被拒绝
"""
import base64
import os
import random
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "test", "resources", "fixtures")

PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

INDEX_HTML = """<!DOCTYPE html>
<html lang="zh-Hans"><head><meta charset="utf-8"/>
<title>测试书 · Test Book</title>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:image" content="cover.png"/>
<link rel="stylesheet" type="text/css" href="assets/style.css"/>
</head><body>
<h1>测试书</h1>
<p>行内公式 $a^2+b^2=c^2$，用来确认正文能被原样取出。</p>
<p><a href="sub/page.html">第二章</a></p>
<img src="assets/pic.png" alt="图"/>
</body></html>
"""

PAGE2 = """<!DOCTYPE html><html><head><meta charset="utf-8"/><title>第二章</title>
<link rel="stylesheet" href="../assets/style.css"/></head>
<body><h2>第二章</h2><p>内容。</p></body></html>
"""

CSS = "body{font-size:20px;color:#000;background:#fff;line-height:1.75}\n" * 40

NOT_FOUND = """<!DOCTYPE html><html><head><meta charset="utf-8"/><title>页面不存在</title>
<link rel="stylesheet" type="text/css" href="/assets/style.css"/></head>
<body><p>页面不存在</p><p><a href="/index.html">回到目录</a></p></body></html>
"""


def build(path, files, compress, prefix=""):
    with zipfile.ZipFile(path, "w") as z:
        for name, data in files.items():
            info = zipfile.ZipInfo(prefix + name)
            info.compress_type = compress
            info.date_time = (2026, 10, 31, 12, 0, 0)
            z.writestr(info, data)
    print("%-24s %7.1f KB" % (os.path.basename(path), os.path.getsize(path) / 1024))


def main():
    os.makedirs(OUT, exist_ok=True)
    random.seed(42)
    big_text = ("KaTeX span content 公式占位 " * 40 + "\n") * 3000   # ~3MB，可压缩
    blob = bytes(random.getrandbits(8) for _ in range(100 * 1024))  # 不可压缩

    files = {
        "index.html": INDEX_HTML,
        "sub/page.html": PAGE2,
        "assets/style.css": CSS,
        "assets/pic.png": PNG_1X1,
        "cover.png": PNG_1X1,
        "404.html": NOT_FOUND,
        "big.txt": big_text,
        "assets/blob.bin": blob,
    }

    build(os.path.join(OUT, "fixture_deflate.zip"), files, zipfile.ZIP_DEFLATED)
    build(os.path.join(OUT, "fixture_store.zip"), files, zipfile.ZIP_STORED)
    build(os.path.join(OUT, "fixture_nested.zip"), files, zipfile.ZIP_DEFLATED, prefix="book/")

    noidx = {k: v for k, v in files.items() if k != "index.html"}
    build(os.path.join(OUT, "fixture_noidx.zip"), noidx, zipfile.ZIP_DEFLATED)


if __name__ == "__main__":
    main()
