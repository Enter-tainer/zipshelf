package moe.mgt.zipshelf.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import moe.mgt.zipshelf.TestFixtures;
import moe.mgt.zipshelf.zip.ZipArchive;

public class HttpServerTest {

    private static HttpServer server;
    private static int port;
    private static ZipArchive deflate;
    private static ZipArchive store;

    @BeforeClass
    public static void setUp() throws Exception {
        File deflateFile = TestFixtures.zip("fixture_deflate.zip");
        File storeFile = TestFixtures.zip("fixture_store.zip");
        deflate = ZipArchive.open(deflateFile);
        store = ZipArchive.open(storeFile);

        Map<String, LibraryHandler.Book> books = new LinkedHashMap<>();
        books.put("d", new TestBook("d", "Deflate 书", deflate));
        books.put("s", new TestBook("s", "Store 书", store));

        server = new HttpServer(new LibraryHandler(() -> books, true), 18900);
        port = server.start();
    }

    @AfterClass
    public static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static final class TestBook implements LibraryHandler.Book {
        private final String id;
        private final String title;
        private final ZipArchive archive;

        TestBook(String id, String title, ZipArchive archive) {
            this.id = id;
            this.title = title;
            this.archive = archive;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public ZipArchive archive() {
            return archive;
        }
    }

    // ------------------------------------------------------------ helpers

    private static HttpURLConnection open(String path) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        return c;
    }

    private static byte[] body(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            return new byte[0];
        }
        try (InputStream src = in) {
            return ZipArchiveTestBytes.readAll(src);
        }
    }

    private static final class ZipArchiveTestBytes {
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

    // ------------------------------------------------------------ tests

    @Test
    public void healthz() throws Exception {
        HttpURLConnection c = open("/healthz");
        assertEquals(200, c.getResponseCode());
        assertEquals("ok\n", new String(body(c), StandardCharsets.UTF_8));
    }

    @Test
    public void rootServesIndexHtml() throws Exception {
        HttpURLConnection c = open("/books/d/");
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().startsWith("text/html"));
        assertArrayEquals(deflate.readAll(deflate.get("index.html")), body(c));
    }

    @Test
    public void redirectsBookWithoutSlash() throws Exception {
        HttpURLConnection c = open("/books/d");
        assertEquals(302, c.getResponseCode());
        assertEquals("/books/d/", c.getHeaderField("Location"));
    }

    @Test
    public void etagGives304() throws Exception {
        HttpURLConnection first = open("/books/d/sub/page.html");
        assertEquals(200, first.getResponseCode());
        String etag = first.getHeaderField("ETag");
        assertNotNull(etag);
        body(first);

        HttpURLConnection second = open("/books/d/sub/page.html");
        second.setRequestProperty("If-None-Match", etag);
        assertEquals(304, second.getResponseCode());
    }

    @Test
    public void rangeOnStoredEntry() throws Exception {
        byte[] all = store.readAll(store.get("assets/blob.bin"));
        HttpURLConnection c = open("/books/s/assets/blob.bin");
        c.setRequestProperty("Range", "bytes=100-199");
        assertEquals(206, c.getResponseCode());
        assertEquals("bytes 100-199/" + all.length, c.getHeaderField("Content-Range"));
        byte[] part = body(c);
        assertEquals(100, part.length);
        for (int i = 0; i < 100; i++) {
            assertEquals(all[100 + i], part[i]);
        }
    }

    @Test
    public void suffixRangeOnStoredEntry() throws Exception {
        HttpURLConnection c = open("/books/s/assets/blob.bin");
        c.setRequestProperty("Range", "bytes=-50");
        assertEquals(206, c.getResponseCode());
        assertEquals(50, body(c).length);
    }

    @Test
    public void unsatisfiableRangeGives416() throws Exception {
        HttpURLConnection c = open("/books/s/assets/blob.bin");
        c.setRequestProperty("Range", "bytes=99999999-");
        assertEquals(416, c.getResponseCode());
    }

    @Test
    public void gzipPassthroughDecodesToOriginalBytes() throws Exception {
        HttpURLConnection c = open("/books/d/big.txt");
        c.setRequestProperty("Accept-Encoding", "gzip");
        assertEquals(200, c.getResponseCode());
        assertEquals("gzip", c.getHeaderField("Content-Encoding"));

        byte[] expected = deflate.readAll(deflate.get("big.txt"));
        try (InputStream in = new GZIPInputStream(c.getInputStream())) {
            assertArrayEquals(expected, ZipArchiveTestBytes.readAll(in));
        }
    }

    @Test
    public void gzipResponseIgnoresRange() throws Exception {
        HttpURLConnection c = open("/books/d/big.txt");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("Range", "bytes=0-99");
        assertEquals(200, c.getResponseCode());
        body(c);
    }

    /** 404 页里的根相对路径必须被改写到 /books/{id}/ 前缀下。 */
    @Test
    public void notFoundUsesBookPageWithRewrittenPaths() throws Exception {
        HttpURLConnection c = open("/books/d/does-not-exist.html");
        assertEquals(404, c.getResponseCode());
        String html = new String(body(c), StandardCharsets.UTF_8);
        assertTrue(html.contains("/books/d/assets/style.css"));
        assertTrue(html.contains("/books/d/index.html"));
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        HttpURLConnection c = open("/books/d/%2e%2e%2fsecret");
        assertEquals(400, c.getResponseCode());
        body(c);
    }

    @Test
    public void unknownBookIs404() throws Exception {
        HttpURLConnection c = open("/books/nope/");
        assertEquals(404, c.getResponseCode());
        body(c);
    }

    /** 同一个连接上连发两次请求，验证 keep-alive 真的工作。 */
    @Test
    public void keepAliveServesTwoRequestsOnOneSocket() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            RawResponse r1 = exchange(in, out, "/healthz", "keep-alive");
            assertEquals(200, r1.status);
            assertEquals("ok\n", new String(r1.body, StandardCharsets.UTF_8));

            RawResponse r2 = exchange(in, out, "/books/d/", "close");
            assertEquals(200, r2.status);
            assertArrayEquals(deflate.readAll(deflate.get("index.html")), r2.body);
        }
    }

    private static final class RawResponse {
        int status;
        byte[] body;
    }

    private static RawResponse exchange(InputStream in, OutputStream out, String path, String connection)
            throws IOException {
        String req = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: " + connection + "\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readLine(in);
        RawResponse resp = new RawResponse();
        resp.status = Integer.parseInt(statusLine.split(" ")[1]);
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        byte[] buf = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(buf, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        resp.body = buf;
        return resp;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] b = buf.toByteArray();
                int n = b.length;
                if (n > 0 && b[n - 1] == '\r') {
                    n--;
                }
                return new String(b, 0, n, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
        }
        throw new IOException("连接提前关闭");
    }
}
