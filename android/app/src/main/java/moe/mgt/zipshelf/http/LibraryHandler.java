package moe.mgt.zipshelf.http;

import moe.mgt.zipshelf.zip.GzipFraming;
import moe.mgt.zipshelf.zip.ZipArchive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把「书架里的书」映射成 URL：
 *
 * <pre>
 *   /                      书架列表（调试 / 可存书签）
 *   /healthz               探活
 *   /books/{id}/           该书 index.html
 *   /books/{id}/{path}     该书的某个条目
 * </pre>
 */
public final class LibraryHandler implements HttpServer.Handler {

    /** 路由只依赖这些能力，方便测试与替换。 */
    public interface Book {
        String id();

        String title();

        ZipArchive archive();

        /** zip 里网站根目录的前缀：根目录为 ""，若整站被包在单层目录里则为 "book/"。 */
        default String entryPrefix() {
            return "";
        }
    }

    private final Supplier<Map<String, Book>> books;
    private final boolean gzipPassthrough;
    private final AssetSource assets;

    /** App 全局资源的保留命名空间，不属于任何一本书。 */
    private static final String APP_NS = "/@/";

    private static final String FONT_CSS =
            "/* zipshelf 提供：完整思源宋可变字体，字重 200-900 */\n"
            + "@font-face {\n"
            + "  font-family: \"Book Serif\";\n"
            + "  src: url(\"/@/fonts/NotoSerifSC-VF.ttf\") format(\"truetype\");\n"
            + "  font-weight: 200 900;\n"
            + "  font-display: swap;\n"
            + "}\n";

    public LibraryHandler(Supplier<Map<String, Book>> books, boolean gzipPassthrough) {
        this(books, gzipPassthrough, null);
    }

    public LibraryHandler(Supplier<Map<String, Book>> books, boolean gzipPassthrough,
                          AssetSource assets) {
        this.books = books;
        this.gzipPassthrough = gzipPassthrough;
        this.assets = assets;
    }

    @Override
    public Response handle(Request req) throws Exception {
        String path = req.path;

        if (path.startsWith(APP_NS)) {
            return appAsset(path);
        }
        if ("/healthz".equals(path)) {
            return Response.text(200, "ok\n");
        }
        if ("/".equals(path)) {
            return indexPage();
        }
        if (!path.startsWith("/books/")) {
            return Response.text(404, "not found\n");
        }

        String rest = path.substring("/books/".length());
        int slash = rest.indexOf('/');
        String id;
        String sub;
        if (slash < 0) {
            id = rest;
            sub = null;
        } else {
            id = rest.substring(0, slash);
            sub = rest.substring(slash + 1);
        }
        if (id.isEmpty()) {
            return Response.text(404, "not found\n");
        }

        Book book = books.get().get(id);
        if (book == null) {
            return Response.text(404, "没有这本书：" + id + "\n");
        }
        if (sub == null) {
            return Response.redirect("/books/" + id + "/");
        }
        if (sub.isEmpty() || sub.endsWith("/")) {
            sub = sub + "index.html";
        }

        ZipArchive archive = book.archive();
        String prefix = book.entryPrefix();
        ZipArchive.Entry entry = archive.get(prefix + sub);
        if (entry == null) {
            entry = archive.getIgnoreCase(prefix + sub);
        }
        if (entry != null && entry.isDirectory()) {
            entry = archive.getIgnoreCase(entry.name + "index.html");
        }
        if (entry == null) {
            return notFound(book, id);
        }

        Response resp = new Response();
        resp.contentType = Mime.forName(entry.name);
        resp.etag = "\"" + Long.toHexString(entry.crc32) + "-" + Long.toHexString(entry.size) + "\"";

        if (entry.method == ZipArchive.METHOD_DEFLATE && gzipPassthrough && req.acceptsGzip()) {
            // 零解压：zip 的 raw deflate 直接包成 gzip 流，浏览器自己解
            resp.contentEncoding = "gzip";
            resp.body = new GzipBody(archive, entry);
        } else if (entry.method == ZipArchive.METHOD_STORE) {
            resp.body = new StoreBody(archive, entry);
        } else {
            resp.body = new InflateBody(archive, entry);
        }
        return resp;
    }

    private Response notFound(Book book, String id) throws IOException {
        ZipArchive archive = book.archive();
        String prefix = book.entryPrefix();
        ZipArchive.Entry e404 = archive.get(prefix + "404.html");
        if (e404 == null) {
            e404 = archive.get("404.html");
        }
        if (e404 == null) {
            return Response.text(404, "页面不存在\n");
        }
        String html = new String(archive.readAll(e404), StandardCharsets.UTF_8);
        // 上游 404.html 用根相对路径（/assets/...），在 /books/{id}/ 前缀下会失效；
        // 这里按书前缀重写，不改 zip 内容。
        String urlPrefix = "/books/" + id + "/";
        html = html.replace("href=\"/", "href=\"" + urlPrefix).replace("src=\"/", "src=\"" + urlPrefix);
        return Response.html(404, html);
    }

    private Response indexPage() {
        Map<String, Book> map = books.get();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!doctype html><html lang=\"zh-Hans\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>书架 · zipshelf</title><style>")
                .append("body{font:20px/1.75 system-ui,sans-serif;color:#000;background:#fff;margin:1.6em 1.2em;}")
                .append("h1{font-size:1.35em;margin:0 0 1em;}ul{list-style:none;padding:0;}")
                .append("li{margin:.9em 0;padding-bottom:.9em;border-bottom:1px solid #000;}")
                .append("a{color:#000;}small{color:#000;opacity:.7;font-size:.8em;}")
                .append("</style></head><body><h1>书架</h1><ul>");
        if (map.isEmpty()) {
            sb.append("<li>还没有导入任何书。</li>");
        }
        for (Book b : map.values()) {
            sb.append("<li><a href=\"/books/").append(escape(b.id())).append("/\">")
                    .append(escape(b.title())).append("</a><br><small>")
                    .append(escape(b.id())).append("</small></li>");
        }
        sb.append("</ul></body></html>");
        return Response.html(200, sb.toString());
    }

    /**
     * App 自带资源。目前只服务正文衬线字体：
     *   /@/font.css                          一行 @font-face，书里可以直接 link
     *   /@/fonts/NotoSerifSC-VF.ttf          完整字体本体
     */
    private Response appAsset(String path) throws IOException {
        if (assets == null) {
            return Response.text(404, "no app assets in this build\n");
        }
        String name = path.substring(APP_NS.length());
        if ("font.css".equals(name)) {
            byte[] css = FONT_CSS.getBytes(StandardCharsets.UTF_8);
            Response r = new Response();
            r.contentType = "text/css; charset=utf-8";
            r.etag = "\"" + Integer.toHexString(css.length) + "\"";
            r.body = new ByteBody(css);
            return r;
        }
        if (!assets.exists(name)) {
            return Response.text(404, "no such app asset: " + name + "\n");
        }
        long length = assets.length(name);
        Response r = new Response();
        r.contentType = Mime.forName(name);
        r.etag = "\"" + Long.toHexString(length) + "\"";
        r.body = new AssetBody(assets, name, length);
        return r;
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------ bodies

    /** App 资源（字体）：一次性流式发出，长度已知。 */
    static final class AssetBody implements Body {
        private final AssetSource assets;
        private final String name;
        private final long length;

        AssetBody(AssetSource assets, String name, long length) {
            this.assets = assets;
            this.name = name;
            this.length = length;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public void writeTo(OutputStream out, long offset, long len) throws IOException {
            try (InputStream in = assets.open(name)) {
                long skip = offset;
                while (skip > 0) {
                    long n = in.skip(skip);
                    if (n <= 0) {
                        break;
                    }
                    skip -= n;
                }
                byte[] buf = new byte[64 * 1024];
                long remaining = len;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int r = in.read(buf, 0, want);
                    if (r < 0) {
                        break;
                    }
                    out.write(buf, 0, r);
                    remaining -= r;
                }
            }
        }
    }

    /** 零解压：zip 的 raw deflate + 自造 gzip 头尾。 */
    static final class GzipBody implements Body {
        private final ZipArchive archive;
        private final ZipArchive.Entry entry;

        GzipBody(ZipArchive archive, ZipArchive.Entry entry) {
            this.archive = archive;
            this.entry = entry;
        }

        @Override
        public long length() {
            return GzipFraming.header().length + entry.compressedSize + GzipFraming.trailer(0, 0).length;
        }

        @Override
        public void writeTo(OutputStream out, long offset, long len) throws IOException {
            if (offset != 0 || len != length()) {
                throw new IOException("gzip 响应不支持 Range");
            }
            out.write(GzipFraming.header());
            try (InputStream in = archive.openCompressedStream(entry)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                }
            }
            out.write(GzipFraming.trailer(entry.crc32, entry.size));
        }
    }

    /** STORE 条目：直接按偏移读文件，支持 Range。 */
    static final class StoreBody implements Body {
        private final ZipArchive archive;
        private final ZipArchive.Entry entry;

        StoreBody(ZipArchive archive, ZipArchive.Entry entry) {
            this.archive = archive;
            this.entry = entry;
        }

        @Override
        public long length() {
            return entry.size;
        }

        @Override
        public boolean supportsRange() {
            return true;
        }

        @Override
        public void writeTo(OutputStream out, long offset, long len) throws IOException {
            byte[] buf = new byte[64 * 1024];
            long pos = offset;
            long remaining = len;
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = archive.readAt(entry, pos, buf, 0, want);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                pos += n;
                remaining -= n;
            }
        }
    }

    /** DEFLATE 条目在客户端不收 gzip 时的兜底：本地解压后发 identity。 */
    static final class InflateBody implements Body {
        private final ZipArchive archive;
        private final ZipArchive.Entry entry;

        InflateBody(ZipArchive archive, ZipArchive.Entry entry) {
            this.archive = archive;
            this.entry = entry;
        }

        @Override
        public long length() {
            return entry.size;
        }

        @Override
        public void writeTo(OutputStream out, long offset, long len) throws IOException {
            try (InputStream in = archive.openStream(entry)) {
                long skip = offset;
                while (skip > 0) {
                    long n = in.skip(skip);
                    if (n <= 0) {
                        break;
                    }
                    skip -= n;
                }
                byte[] buf = new byte[64 * 1024];
                long remaining = len;
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int r = in.read(buf, 0, want);
                    if (r < 0) {
                        break;
                    }
                    out.write(buf, 0, r);
                    remaining -= r;
                }
            }
        }
    }
}
