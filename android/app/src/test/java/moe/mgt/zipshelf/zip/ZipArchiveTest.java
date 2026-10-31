package moe.mgt.zipshelf.zip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import moe.mgt.zipshelf.TestFixtures;

public class ZipArchiveTest {

    @Test
    public void parsesCentralDirectoryAndOffsets() throws Exception {
        File f = TestFixtures.zip("fixture_deflate.zip");
        ZipArchive archive = ZipArchive.open(f);

        assertEquals(8, archive.size());
        assertTrue(archive.has("index.html"));
        assertTrue(archive.has("sub/page.html"));

        long fileLen = f.length();
        for (ZipArchive.Entry e : archive.entries()) {
            assertTrue(e.dataOffset > 0);
            assertTrue(e.dataOffset + e.compressedSize <= fileLen);
            assertEquals(ZipArchive.METHOD_DEFLATE, e.method);
        }
        // 文本类条目 deflate 后一定更小；png 这种已压缩的反而可能变大
        ZipArchive.Entry big = archive.get("big.txt");
        assertTrue(big.compressedSize < big.size / 10);
    }

    @Test
    public void decompressedBytesMatchJavaZipFile() throws Exception {
        File f = TestFixtures.zip("fixture_deflate.zip");
        ZipArchive archive = ZipArchive.open(f);

        try (ZipFile reference = new ZipFile(f)) {
            for (ZipArchive.Entry e : archive.entries()) {
                assertNotNull("ZipFile 里没有 " + e.name, reference.getEntry(e.name));
                byte[] expected;
                try (InputStream in = reference.getInputStream(reference.getEntry(e.name))) {
                    expected = readAll(in);
                }
                assertArrayEquals(e.name, expected, archive.readAll(e));
            }
        }
    }

    @Test
    public void storedEntriesMatchAndSupportOffsetReads() throws Exception {
        File f = TestFixtures.zip("fixture_store.zip");
        ZipArchive archive = ZipArchive.open(f);
        ZipArchive.Entry blob = archive.get("assets/blob.bin");
        assertNotNull(blob);
        assertEquals(ZipArchive.METHOD_STORE, blob.method);
        assertEquals(blob.size, blob.compressedSize);

        byte[] all = archive.readAll(blob);
        assertEquals(blob.size, all.length);

        byte[] slice = new byte[100];
        int n = archive.readAt(blob, 1234, slice, 0, slice.length);
        assertEquals(100, n);
        for (int i = 0; i < 100; i++) {
            assertEquals("byte " + i, all[1234 + i], slice[i]);
        }
    }

    /** 核心机制：raw deflate + 自造 gzip 头尾 == 原文件。 */
    @Test
    public void gzipFramingRoundTripsEveryEntry() throws Exception {
        File f = TestFixtures.zip("fixture_deflate.zip");
        ZipArchive archive = ZipArchive.open(f);
        for (ZipArchive.Entry e : archive.entries()) {
            if (e.method != ZipArchive.METHOD_DEFLATE) {
                continue;
            }
            byte[] raw = archive.readCompressed(e);
            byte[] framed = GzipFraming.wrap(raw, e.crc32, e.size);
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(framed))) {
                assertArrayEquals("gzip 往返失败: " + e.name, archive.readAll(e), readAll(gz));
            }
        }
    }

    @Test
    public void readHeadStopsAtLimit() throws Exception {
        File f = TestFixtures.zip("fixture_deflate.zip");
        ZipArchive archive = ZipArchive.open(f);
        byte[] head = archive.readHead(archive.get("big.txt"), 32);
        assertEquals(32, head.length);
        assertEquals("KaTeX span content", new String(head, 0, 18, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsMissingCentralDirectory() throws IOException {
        File tmp = File.createTempFile("not-zip", ".zip");
        tmp.deleteOnExit();
        java.nio.file.Files.write(tmp.toPath(), "hello, not a zip".getBytes(StandardCharsets.UTF_8));
        try {
            ZipArchive.open(tmp);
            fail("应当抛异常");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("zip"));
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
