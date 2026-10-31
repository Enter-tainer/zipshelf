#!/usr/bin/env python3
"""生成一个 Magisk 字体模块：把完整思源宋（可变字体）装进系统，注册字体家族。

生成的模块做两件事：
  1. /system/fonts/NotoSerifSC-VF.ttf                    整份字体（31,058 字形，字重 200-900）
  2. /system/etc/fonts.xml                               只插入一个新 <family>，其余逐字节不变

插入的家族用 ROM 自己支持的写法（<axis tag="wght" stylevalue="N"/>），
所以一个可变字体文件就能提供 400/500/600/700 四个真字重。

用法：
    python make_font_module.py --fonts-xml <设备拉下来的 fonts.xml> \
        --font <NotoSerifSC-VF.ttf> --out zipshelf-serif.zip
"""
from __future__ import annotations

import argparse
import os
import shutil
import zipfile
from xml.etree import ElementTree

FAMILY = "Zipshelf Serif"

UPDATE_BINARY = r"""#!/sbin/sh
umask 022
ui_print() { echo "$1"; }
require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}
OUTFD=$2
ZIPFILE=$3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ "$MAGISK_VER_CODE" -lt 20400 ] && require_new_magisk
install_module
exit 0
"""

MODULE_PROP = """id=zipshelf-serif
name=zipshelf 思源宋（系统字体）
version=v1.0
versionCode=1
author=mgt
description=安装完整思源宋可变字体（Noto Serif SC，31,058 字形，字重 200-900），并注册系统字体家族 "Zipshelf Serif"（400/500/600/700 四个真字重）。装好后任何浏览器/App 直接写 font-family: "Zipshelf Serif" 即可。
"""

CUSTOMIZE = r"""#!/system/bin/sh
ui_print "- zipshelf 思源宋：完整字体，不做子集"
ui_print "- 字重：400 / 500 / 600 / 700（同一份可变字体）"
ui_print "- 家族名：Zipshelf Serif（另 alias: book-serif）"
ui_print "- 重启后生效"
set_perm_recursive "$MODPATH" 0 0 0755 0644
"""

FAMILY_XML = """
    <!-- zipshelf: 完整思源宋（可变字体，31,058 字形）。四个字重指向同一份文件，
         用 <axis> 指定 wght，与系统自带的 NotoSerifEthiopic-VF 写法一致。 -->
    <family name="Zipshelf Serif">
        <font weight="400" style="normal">NotoSerifSC-VF.ttf
            <axis tag="wght" stylevalue="400"/>
        </font>
        <font weight="500" style="normal">NotoSerifSC-VF.ttf
            <axis tag="wght" stylevalue="500"/>
        </font>
        <font weight="600" style="normal">NotoSerifSC-VF.ttf
            <axis tag="wght" stylevalue="600"/>
        </font>
        <font weight="700" style="normal">NotoSerifSC-VF.ttf
            <axis tag="wght" stylevalue="700"/>
        </font>
        <alias name="book-serif" to="Zipshelf Serif" />
        <alias name="Zipshelf Serif Medium" to="Zipshelf Serif" weight="500" />
    </family>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fonts-xml", required=True, help="设备上 /system/etc/fonts.xml")
    ap.add_argument("--font", required=True, help="NotoSerifSC-VF.ttf（完整可变字体）")
    ap.add_argument("--out", required=True, help="输出模块 zip")
    ap.add_argument("--work", default="build/font_module/work")
    args = ap.parse_args()

    original = open(args.fonts_xml, encoding="utf-8").read()
    if "</familyset>" not in original:
        raise SystemExit("fonts.xml 里找不到 </familyset>，结构不认识")
    if FAMILY in original:
        raise SystemExit("fonts.xml 里已经有 %s 家族了" % FAMILY)

    patched = original.replace("</familyset>", FAMILY_XML + "</familyset>")

    # 校验：能解析、只多一个 family
    ElementTree.fromstring(original)
    root = ElementTree.fromstring(patched)
    n_orig = len(ElementTree.fromstring(original).findall("family"))
    n_new = len(root.findall("family"))
    assert n_new == n_orig + 1, "family 数量不对：%d -> %d" % (n_orig, n_new)
    ours = [f for f in root.findall("family") if f.get("name") == FAMILY]
    assert len(ours) == 1, "没有找到新插入的家族"

    work = args.work
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "system", "etc"), exist_ok=True)
    os.makedirs(os.path.join(work, "system", "fonts"), exist_ok=True)
    os.makedirs(os.path.join(work, "META-INF", "com", "google", "android"), exist_ok=True)

    open(os.path.join(work, "system", "etc", "fonts.xml"), "w", encoding="utf-8").write(patched)
    shutil.copyfile(args.font, os.path.join(work, "system", "fonts", "NotoSerifSC-VF.ttf"))
    open(os.path.join(work, "module.prop"), "w", encoding="utf-8").write(MODULE_PROP)
    open(os.path.join(work, "customize.sh"), "w", encoding="utf-8").write(CUSTOMIZE)
    open(os.path.join(work, "META-INF", "com", "google", "android", "update-binary"),
         "w", encoding="utf-8").write(UPDATE_BINARY)
    open(os.path.join(work, "META-INF", "com", "google", "android", "updater-script"),
         "w", encoding="utf-8").write("#MAGISK\n")

    if os.path.exists(args.out):
        os.remove(args.out)
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        for cur, _dirs, files in os.walk(work):
            for f in sorted(files):
                full = os.path.join(cur, f)
                rel = os.path.relpath(full, work).replace(os.sep, "/")
                z.write(full, rel)

    print("模块: %s  (%.1f MB)" % (args.out, os.path.getsize(args.out) / 1048576))
    print("  family 数: %d -> %d" % (n_orig, n_new))
    print("  fonts.xml: %.0f KB（仅插入一个 family 块）" % (os.path.getsize(
        os.path.join(work, "system", "etc", "fonts.xml")) / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
