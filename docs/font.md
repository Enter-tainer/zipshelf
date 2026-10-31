# 系统字体：思源宋 Medium

正文想用「比 Regular 稍微粗一点」的思源宋。做法不是在书里塞字体，而是**把字体装进系统**：

- 所有书、所有浏览器、所有 App 都能用同一个字体族，不需要 `@font-face`、不需要 App 注入
- 书里的 CSS 只写族名：`font-family: "Zipshelf Serif", …`
- 没装模块时自动回退到系统自带的思源宋（`Noto Serif CJK`，本机就是 Regular）+ 宋体

## 为什么不能直接改 `/system`

`/system/fonts` 和 `/system/etc/fonts.xml` 只有 root 能写，而且改了要过动态分区（AVB）。
本机装了 Magisk **App** 但 root 没生效（没有 `su`、`/proc/mounts` 里没有 magisk overlay、
无 `magiskd` 进程），所以要先修好 Magisk 才能装。

## 模块做了什么

`zipshelf-serif-magisk.zip`：

```
module.prop
customize.sh
META-INF/com/google/android/{update-binary,updater-script}
system/fonts/NotoSerifSC-VF.ttf     整份思源宋可变字体（31,058 字形，24 MB，未裁剪）
system/etc/fonts.xml                设备上的原文件 + 只插入一个新 <family>
```

插入的家族（其余 158 个 family 逐字节不变）：

```xml
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
```

一个可变字体文件 + `<axis>` 就能提供 400/500/600/700 四个**真字重**（不是合成加粗）。
这个写法是照着本机 ROM 自己的 `NotoSerifEthiopic-VF.ttf` 抄的，不是发明。

## 安装

1. 先确认 root 可用：`adb shell su -c id` 能返回 `uid=0`
2. Magisk App → 模块 → 从本地安装 → 选 `zipshelf-serif-magisk.zip` → 重启
3. 验证：打开任意书，正文应是 Medium；或看
   `adb shell ls -l /system/fonts/NotoSerifSC-VF.ttf` 与
   `adb shell grep -c "Zipshelf Serif" /system/etc/fonts.xml`（应为 3）

## 回滚

- Magisk App → 模块 → 关闭/删除 `zipshelf-serif` → 重启
- 如果开不了机：Magisk 安全模式（开机时按住音量下）会自动禁用所有模块；
  或用 recovery 执行 `magisk --remove-modules`

## 风险与注意

- `fonts.xml` 是**整文件覆盖**：ROM 升级后需要重新拉取、重新生成（见下）
- 只插入了一个 family，不动系统原有字体映射，风险较低；但字体配置写坏会影响开机，
  所以保留回滚手段
- 模块只注册家族，不替换系统默认字体，因此**不会**把整个系统 UI 变成 Medium

## 重新生成（换字体/换机器）

```bash
# 1. 拉设备上的字体配置
adb pull /system/etc/fonts.xml build/font_module/fonts.xml.orig

# 2. 生成模块（字体用完整的 Noto Serif SC 可变字体）
python tools/make_font_module.py \
    --fonts-xml build/font_module/fonts.xml.orig \
    --font /path/to/NotoSerifSC-VF.ttf \
    --out zipshelf-serif-magisk.zip
```

生成时会校验：XML 能解析、family 数只 +1、能按名字找到新家族。

## 书那边要做什么

只有一行——CSS 里写族名，然后声明想要的字重：

```css
body {
  font-family: "Zipshelf Serif", "Noto Serif CJK SC", "Source Han Serif SC",
               "Songti SC", "SimSun", "Noto Serif", Georgia, serif;
  font-size: 1.2rem;
  line-height: 1.6;
  font-weight: 500;      /* 有模块 → 真 Medium；没模块 → 回退 Regular */
}
```

`skills/webshelf-book/templates/assets/style.css` 已经是这份配置。
