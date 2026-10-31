package moe.mgt.zipshelf.zip;

/**
 * 把 zip 里的 raw deflate 数据包成合法的 gzip 流。
 *
 * zip 的中央目录已经存了 deflate 后数据的 CRC32 与解压后长度，gzip 的尾部恰好就是
 * 这两样东西，所以不需要解压就能拼出一条标准 gzip 流：
 *
 * <pre>
 *   10 字节 gzip 头 + zip 里的 raw deflate 字节 + 8 字节 gzip 尾
 * </pre>
 *
 * 这样 App 完全不用解压，浏览器（Chromium / EinkBro）自己解。
 */
public final class GzipFraming {

    private GzipFraming() {
    }

    /** 10 字节 gzip 头：magic、deflate、无 flag、mtime=0、XFL=0、OS=255。 */
    public static byte[] header() {
        return new byte[] {
                0x1f, (byte) 0x8b, // magic
                0x08,             // compression: deflate
                0x00,             // flags
                0x00, 0x00, 0x00, 0x00, // mtime
                0x00,             // XFL
                (byte) 0xff       // OS: unknown
        };
    }

    /** 8 字节 gzip 尾：CRC32 + ISIZE（都是小端）。 */
    public static byte[] trailer(long crc32, long uncompressedSize) {
        return new byte[] {
                (byte) (crc32 & 0xFF),
                (byte) ((crc32 >>> 8) & 0xFF),
                (byte) ((crc32 >>> 16) & 0xFF),
                (byte) ((crc32 >>> 24) & 0xFF),
                (byte) (uncompressedSize & 0xFF),
                (byte) ((uncompressedSize >>> 8) & 0xFF),
                (byte) ((uncompressedSize >>> 16) & 0xFF),
                (byte) ((uncompressedSize >>> 24) & 0xFF)
        };
    }

    /** 拼出完整 gzip 流（仅用于测试与兜底；正常路径是分三段直接写 socket）。 */
    public static byte[] wrap(byte[] rawDeflate, long crc32, long uncompressedSize) {
        byte[] head = header();
        byte[] tail = trailer(crc32, uncompressedSize);
        byte[] out = new byte[head.length + rawDeflate.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(rawDeflate, 0, out, head.length, rawDeflate.length);
        System.arraycopy(tail, 0, out, head.length + rawDeflate.length, tail.length);
        return out;
    }
}
