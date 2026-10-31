package moe.mgt.zipshelf.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * App 自带资源（字体等）的读取接口。放在这里是为了让 http 包保持纯 Java、可单测，
 * 具体实现由 android 侧的 {@code ApkAssets} 提供。
 */
public interface AssetSource {

    boolean exists(String path);

    long length(String path) throws IOException;

    InputStream open(String path) throws IOException;
}
