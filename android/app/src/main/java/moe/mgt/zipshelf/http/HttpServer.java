package moe.mgt.zipshelf.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极小的 HTTP/1.1 服务端：只支持 GET/HEAD，但支持 keep-alive、单段 Range、
 * ETag/304、Content-Encoding: gzip 透传。
 *
 * 只监听回环地址——这是本地阅读场景，不需要外部可达。
 */
public final class HttpServer {

    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_REQUESTS_PER_CONNECTION = 400;
    private static final int IDLE_TIMEOUT_MS = 60_000;

    private final Handler handler;
    private final int basePort;
    private final ExecutorService pool;
    private volatile ServerSocket server;
    private volatile boolean running;

    public HttpServer(Handler handler, int basePort) {
        this.handler = handler;
        this.basePort = basePort;
        this.pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "http-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动并返回实际端口（从 basePort 起找可用端口）。 */
    public int start() throws IOException {
        IOException last = null;
        for (int i = 0; i < 24; i++) {
            int port = basePort + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 32);
                server = ss;
                break;
            } catch (BindException e) {
                last = e;
            }
        }
        if (server == null) {
            throw last != null ? last : new IOException("无法绑定端口");
        }
        running = true;
        Thread accept = new Thread(this::acceptLoop, "http-accept");
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(IDLE_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            for (int n = 0; n < MAX_REQUESTS_PER_CONNECTION && running; n++) {
                Request req;
                try {
                    req = parse(in);
                } catch (IllegalArgumentException badPath) {
                    Response bad = Response.text(400, "bad request\n");
                    writeResponse(out, new Request("GET", "/", "/", null, new HashMap<>()), bad, false);
                    out.flush();
                    return;
                }
                if (req == null) {
                    return;
                }
                boolean keepAlive = wantsKeepAlive(req);
                Response resp;
                try {
                    resp = handler.handle(req);
                } catch (IllegalArgumentException badPath) {
                    resp = Response.text(400, "bad request\n");
                } catch (Exception e) {
                    resp = Response.text(500, "internal error: " + e + "\n");
                }
                if (resp == null) {
                    resp = Response.text(404, "not found\n");
                }
                writeResponse(out, req, resp, keepAlive);
                out.flush();
                if (!keepAlive) {
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            // 空闲连接，正常
        } catch (IOException e) {
            // 客户端断开，正常
        }
    }

    // ---------------------------------------------------------------- parse

    private static Request parse(InputStream in) throws IOException {
        String line;
        do {
            line = readLine(in);
            if (line == null) {
                return null;
            }
        } while (line.isEmpty());

        String[] parts = line.split(" ");
        if (parts.length < 3) {
            throw new IOException("bad request line: " + line);
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        String version = parts[2];

        Map<String, String> headers = new HashMap<>();
        int total = 0;
        for (int i = 0; i < MAX_HEADERS; i++) {
            String h = readLine(in);
            if (h == null || h.isEmpty()) {
                break;
            }
            total += h.length();
            if (total > 64 * 1024) {
                throw new IOException("headers too large");
            }
            int colon = h.indexOf(':');
            if (colon > 0) {
                headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
        }
        if (!version.toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            throw new IOException("bad version");
        }

        String rawPath = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            rawPath = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if (!rawPath.startsWith("/")) {
            // absolute-form（代理风格）或 "*"，一律当坏请求
            throw new IOException("bad target");
        }
        String path = UrlPath.normalize(UrlPath.decode(rawPath));
        return new Request(method, target, path, query, headers);
    }

    private static boolean wantsKeepAlive(Request req) {
        String c = req.header("connection");
        if (c != null) {
            String v = c.toLowerCase(Locale.ROOT);
            if (v.contains("close")) {
                return false;
            }
            if (v.contains("keep-alive")) {
                return true;
            }
        }
        return true; // HTTP/1.1 默认长连接
    }

    // ---------------------------------------------------------------- write

    private void writeResponse(OutputStream out, Request req, Response resp, boolean keepAlive) throws IOException {
        Body body = resp.body;
        long length = body == null ? 0 : body.length();

        boolean notModified = false;
        if (resp.etag != null && body != null) {
            String inm = req.header("if-none-match");
            if (inm != null && (inm.contains(resp.etag) || "*".equals(inm.trim()))) {
                notModified = true;
            }
        }

        long rangeStart = -1;
        long rangeEnd = -1;
        boolean partial = false;
        boolean unsatisfiable = false;

        if (!notModified && body != null && resp.status == 200
                && resp.contentEncoding == null && body.supportsRange() && length > 0) {
            String rangeHeader = req.header("range");
            if (rangeHeader != null) {
                long[] r = parseRange(rangeHeader, length);
                if (r == UNSATISFIABLE) {
                    unsatisfiable = true;
                } else if (r != null) {
                    rangeStart = r[0];
                    rangeEnd = r[1];
                    partial = true;
                }
            }
        }

        int status = unsatisfiable ? 416 : (notModified ? 304 : (partial ? 206 : resp.status));
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (resp.contentType != null) {
            sb.append("Content-Type: ").append(resp.contentType).append("\r\n");
        }
        if (resp.contentEncoding != null) {
            sb.append("Content-Encoding: ").append(resp.contentEncoding).append("\r\n");
        }
        if (resp.etag != null) {
            sb.append("ETag: ").append(resp.etag).append("\r\n");
        }
        if (resp.cacheControl != null) {
            sb.append("Cache-Control: ").append(resp.cacheControl).append("\r\n");
        }
        if (body != null && body.supportsRange()) {
            sb.append("Accept-Ranges: bytes\r\n");
        }
        if (partial) {
            sb.append("Content-Range: bytes ").append(rangeStart).append('-').append(rangeEnd)
                    .append('/').append(length).append("\r\n");
        } else if (unsatisfiable) {
            sb.append("Content-Range: bytes */").append(length).append("\r\n");
        }
        for (Map.Entry<String, String> e : resp.extraHeaders.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        if (status != 304) {
            long outLen = partial ? (rangeEnd - rangeStart + 1) : (unsatisfiable ? 0 : length);
            sb.append("Content-Length: ").append(outLen).append("\r\n");
        }
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

        if (status == 304 || unsatisfiable || body == null || "HEAD".equals(req.method)) {
            return;
        }
        if (partial) {
            body.writeTo(out, rangeStart, rangeEnd - rangeStart + 1);
        } else {
            body.writeTo(out, 0, length);
        }
    }

    private static final long[] UNSATISFIABLE = new long[0];

    /**
     * 解析单段 Range。返回 null 表示忽略（多段或格式不认识），返回 {@link #UNSATISFIABLE}
     * 表示范围越界（回 416）。
     */
    static long[] parseRange(String header, long length) {
        String h = header.trim();
        if (!h.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            return null;
        }
        String spec = h.substring(6).trim();
        if (spec.contains(",")) {
            return null; // 多段：直接给全量
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            if (a.isEmpty()) {
                if (b.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(b);
                if (suffix <= 0) {
                    return UNSATISFIABLE;
                }
                long start = Math.max(0, length - suffix);
                return new long[] {start, length - 1};
            }
            long start = Long.parseLong(a);
            long end = b.isEmpty() ? length - 1 : Long.parseLong(b);
            if (start >= length) {
                return UNSATISFIABLE;
            }
            if (start < 0) {
                return null;
            }
            end = Math.min(end, length - 1);
            if (end < start) {
                return UNSATISFIABLE;
            }
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] bytes = buf.toByteArray();
                int n = bytes.length;
                if (n > 0 && bytes[n - 1] == '\r') {
                    n--;
                }
                return new String(bytes, 0, n, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
            if (buf.size() > MAX_LINE) {
                throw new IOException("line too long");
            }
        }
        if (buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
