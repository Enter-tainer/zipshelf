package moe.mgt.zipshelf.http;

import java.io.IOException;
import java.io.OutputStream;

/** 内存里的字节响应体，支持 Range。 */
public final class ByteBody implements Body {

    private final byte[] bytes;

    public ByteBody(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public long length() {
        return bytes.length;
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public void writeTo(OutputStream out, long offset, long len) throws IOException {
        int off = (int) Math.min(offset, bytes.length);
        int n = (int) Math.min(len, bytes.length - off);
        if (n > 0) {
            out.write(bytes, off, n);
        }
    }
}
