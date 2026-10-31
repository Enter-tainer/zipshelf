package moe.mgt.zipshelf.zip;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只读 zip 解析器。
 *
 * 不用 {@link java.util.zip.ZipFile}，因为需要每个条目的 data offset 和 CRC——
 * 有了它们才能把 zip 里的 raw deflate 数据原样包成 gzip 流发给浏览器（零解压），
 * 见 {@link GzipFraming}。
 *
 * 只支持 STORE(0) 与 DEFLATE(8)，拒绝 ZIP64 与加密条目。
 */
public final class ZipArchive {

    public static final int METHOD_STORE = 0;
    public static final int METHOD_DEFLATE = 8;

    public static final class Entry {
        public final String name;
        public final int method;
        public final long crc32;
        public final long compressedSize;
        public final long size;
        /** 条目数据在文件中的绝对偏移（已跳过 local header）。 */
        public final long dataOffset;

        Entry(String name, int method, long crc32, long compressedSize, long size, long dataOffset) {
            this.name = name;
            this.method = method;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.size = size;
            this.dataOffset = dataOffset;
        }

        public boolean isDirectory() {
            return name.endsWith("/");
        }

        @Override
        public String toString() {
            return name + " [" + method + " " + compressedSize + "/" + size + " @" + dataOffset + "]";
        }
    }

    private final File file;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ZipArchive(File file, List<Entry> entries) {
        this.file = file;
        this.entries = Collections.unmodifiableList(entries);
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            m.putIfAbsent(e.name, e);
        }
        this.byName = Collections.unmodifiableMap(m);
    }

    public File file() {
        return file;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry get(String name) {
        return byName.get(name);
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    public int size() {
        return entries.size();
    }

    // ---------------------------------------------------------------- open

    public static ZipArchive open(File f) throws IOException {
        List<Entry> entries = readCentralDirectory(f);
        return new ZipArchive(f, entries);
    }

    private static List<Entry> readCentralDirectory(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long fileLen = raf.length();
            if (fileLen < 22) {
                throw new IOException("不是 zip：文件太小");
            }

            int tailLen = (int) Math.min(fileLen, 66 * 1024L); // 22 + max comment 65535
            byte[] tail = new byte[tailLen];
            raf.seek(fileLen - tailLen);
            raf.readFully(tail);

            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("不是 zip：找不到中央目录结尾");
            }

            int totalEntries = u16(tail, eocd + 10);
            long cdSize = u32(tail, eocd + 12);
            long cdOffset = u32(tail, eocd + 16);

            if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                throw new IOException("不支持 ZIP64（>4GB 或 >65535 个条目）");
            }
            if (cdSize > 32L * 1024 * 1024) {
                throw new IOException("中央目录异常大：" + cdSize);
            }
            if (cdOffset + cdSize > fileLen) {
                throw new IOException("不是 zip：中央目录越界");
            }

            // 兼容「头部被追加过数据」的 zip（如自解压包）：按 EOCD 位置反推真实偏移。
            long eocdAbs = fileLen - tailLen + eocd;
            long adjust = (eocdAbs - cdSize) - cdOffset;

            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset + adjust);
            raf.readFully(cd);

            List<Entry> out = new ArrayList<>(totalEntries);
            int p = 0;
            for (int i = 0; i < totalEntries; i++) {
                if (p + 46 > cd.length || u32(cd, p) != 0x02014b50L) {
                    throw new IOException("中央目录损坏（第 " + i + " 项）");
                }
                int flags = u16(cd, p + 8);
                int method = u16(cd, p + 10);
                long crc = u32(cd, p + 16);
                long csize = u32(cd, p + 20);
                long usize = u32(cd, p + 24);
                int nameLen = u16(cd, p + 28);
                int extraLen = u16(cd, p + 30);
                int commentLen = u16(cd, p + 32);
                long lho = u32(cd, p + 42);

                if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                    throw new IOException("不支持 ZIP64 条目：" + "(index " + i + ")");
                }
                if ((flags & 0x0001) != 0) {
                    throw new IOException("不支持加密的 zip 条目");
                }
                if (method != METHOD_STORE && method != METHOD_DEFLATE) {
                    throw new IOException("不支持的压缩方式 " + method + "（只支持 store/deflate）");
                }

                int nameStart = p + 46;
                if (nameStart + nameLen > cd.length) {
                    throw new IOException("中央目录损坏（文件名越界）");
                }
                // bit 11 = 文件名是 UTF-8。很多打包器即使写 UTF-8 也不置位，所以
                // 先按 UTF-8 严格解码，失败再退回 CP437（Windows 老工具）。
                byte[] nameBytes = new byte[nameLen];
                System.arraycopy(cd, nameStart, nameBytes, 0, nameLen);
                String name = decodeName(nameBytes, (flags & 0x0800) != 0);

                long realLho = lho + adjust;
                long dataOffset = dataOffset(raf, realLho, name);
                out.add(new Entry(name, method, crc, csize, usize, dataOffset));

                p = nameStart + nameLen + extraLen + commentLen;
            }
            return out;
        }
    }

    private static String decodeName(byte[] bytes, boolean utf8Flag) {
        if (utf8Flag) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static long dataOffset(RandomAccessFile raf, long localHeaderOffset, String name) throws IOException {
        if (localHeaderOffset < 0) {
            throw new IOException("条目偏移非法：" + name);
        }
        raf.seek(localHeaderOffset);
        byte[] h = new byte[30];
        raf.readFully(h);
        if (u32(h, 0) != 0x04034b50L) {
            throw new IOException("local header 损坏：" + name);
        }
        int nameLen = u16(h, 26);
        int extraLen = u16(h, 28);
        return localHeaderOffset + 30 + nameLen + extraLen;
    }

    // ---------------------------------------------------------------- read

    /** 打开条目解压后的流。DEFLATE 用 raw inflate（nowrap=true）。 */
    public InputStream openStream(Entry e) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        raf.seek(e.dataOffset);
        InputStream raw = new BoundedInputStream(raf, e.compressedSize);
        if (e.method == METHOD_STORE) {
            return raw;
        }
        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
        return new java.util.zip.InflaterInputStream(raw, inf, 64 * 1024);
    }

    /** 打开条目「压缩后原始字节」的流（用于 gzip 透明转发，不解压）。 */
    public InputStream openCompressedStream(Entry e) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        raf.seek(e.dataOffset);
        return new BoundedInputStream(raf, e.compressedSize);
    }

    /** 原样读出压缩后的字节（用于 gzip 透明转发）。 */
    public byte[] readCompressed(Entry e) throws IOException {
        if (e.compressedSize > 64L * 1024 * 1024) {
            throw new IOException("条目过大：" + e.name);
        }
        byte[] buf = new byte[(int) e.compressedSize];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(e.dataOffset);
            raf.readFully(buf);
        }
        return buf;
    }

    /** 读解压后的前 maxBytes 字节（摘要/title 提取用）。 */
    public byte[] readHead(Entry e, int maxBytes) throws IOException {
        byte[] buf = new byte[maxBytes];
        int n = 0;
        try (InputStream in = openStream(e)) {
            while (n < maxBytes) {
                int r = in.read(buf, n, maxBytes - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
        }
        if (n == maxBytes) {
            return buf;
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    public byte[] readAll(Entry e) throws IOException {
        if (e.size > 64L * 1024 * 1024) {
            throw new IOException("条目过大：" + e.name);
        }
        byte[] out = new byte[(int) e.size];
        int n = 0;
        try (InputStream in = openStream(e)) {
            while (n < out.length) {
                int r = in.read(out, n, out.length - n);
                if (r < 0) {
                    throw new EOFException("条目数据不完整：" + e.name);
                }
                n += r;
            }
        }
        return out;
    }

    /** 读取指定范围（用于 HTTP Range，只对 STORE 有意义）。 */
    public int readAt(Entry e, long offsetInEntry, byte[] dst, int dstOff, int len) throws IOException {
        long start = e.dataOffset + offsetInEntry;
        int n = (int) Math.min(len, e.compressedSize - offsetInEntry);
        if (n <= 0) {
            return -1;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(dst, dstOff, n);
            return n;
        }
    }

    /** 大小写不敏感的查找（容忍打包器改写大小写）。 */
    public Entry getIgnoreCase(String name) {
        Entry e = byName.get(name);
        if (e != null) {
            return e;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Entry cand : entries) {
            if (cand.name.toLowerCase(Locale.ROOT).equals(lower)) {
                return cand;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static final class BoundedInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;
        private boolean closed;

        BoundedInputStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            if (remaining <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, remaining);
            int r = raf.read(b, off, n);
            if (r > 0) {
                remaining -= r;
            }
            return r;
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, remaining));
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                raf.close();
            }
        }
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }
}
