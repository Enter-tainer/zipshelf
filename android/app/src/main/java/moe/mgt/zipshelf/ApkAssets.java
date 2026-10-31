package moe.mgt.zipshelf;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

import moe.mgt.zipshelf.http.AssetSource;

/**
 * APK assets 里的资源（正文衬线字体）。
 *
 * 字体是**完整**的思源宋可变字体（24 MB，31058 个字形，字重 200–900），不做子集：
 * 所有书共用同一份，任何汉字、任何字重都能用。换字体只需换 APK。
 *
 * ttf 在 build.gradle 里设了 noCompress，因此能用 openFd 直接拿到长度，
 * 响应就带有准确的 Content-Length（字体不需要 Range，浏览器一次性取走并长期缓存）。
 */
public final class ApkAssets implements AssetSource {

    private static final String TAG = "zipshelf-assets";

    private final AssetManager manager;

    private ApkAssets(AssetManager manager) {
        this.manager = manager;
    }

    public static ApkAssets create(Context context) {
        return new ApkAssets(context.getAssets());
    }

    @Override
    public boolean exists(String path) {
        try (InputStream ignored = manager.open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public long length(String path) throws IOException {
        try (AssetFileDescriptor fd = manager.openFd(path)) {
            return fd.getLength();
        } catch (IOException notUncompressed) {
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = manager.open(path)) {
                int r;
                while ((r = in.read(buf)) >= 0) {
                    total += r;
                }
            }
            return total;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        Log.d(TAG, "open " + path);
        return manager.open(path);
    }
}
