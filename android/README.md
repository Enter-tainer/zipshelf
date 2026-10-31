# zipshelf（Android）

墨水屏网页书容器。完整说明见仓库根目录的 [README](../README.md) 和 [设计文档](../docs/design.md)。

## 构建

需要 JDK 17、Android SDK 35（`compileSdk 35`）。

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`keystore/debug.jks` 是可选的本地签名密钥（不入库）；不存在时自动用 AGP 默认 debug 签名。

如果 `~/.gradle` 不可写（沙箱 / CI）：

```bash
export GRADLE_USER_HOME=$PWD/../.gradle-home
export GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches
```

## 代码结构

```
app/src/main/java/moe/mgt/zipshelf/
  zip/ZipArchive.java      自己解析 zip 中央目录（需要 data offset 与 CRC）
  zip/GzipFraming.java     raw deflate → 合规 gzip 流
  http/HttpServer.java     ServerSocket + 线程池，GET/HEAD、keep-alive、Range、ETag
  http/LibraryHandler.java /books/{id}/... 路由、MIME、gzip 转发、404 shim
  data/BookStore.java      导入、校验、标题/封面提取、meta.properties
  ShelfActivity.java       书架、SAF 导入、inbox 扫描、服务开关
  ServerService.java       前台服务，承载 HTTP server
  ServerCore.java          进程内单例（端口、书单快照）
  BookAdapter.java         书架列表 + 1-bit 兜底封面
app/src/test/java/...      22 项纯 JVM 单测（不需要设备）
app/src/test/resources/fixtures/  测试用 zip（合成的，可直接用）
```

## 常用 adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8899 tcp:8787
curl -s http://127.0.0.1:8899/healthz
```
