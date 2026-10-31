package moe.mgt.zipshelf.http;

import java.util.Collections;
import java.util.Map;

/** 一个已解析的 HTTP 请求（只关心 GET/HEAD）。 */
public final class Request {

    public final String method;
    /** 原始 target，例如 {@code /books/abc/ch1.html?x=1}。 */
    public final String target;
    /** 解码并归一化后的路径，带前导斜杠；目录保留结尾斜杠。 */
    public final String path;
    /** 查询串（不含 ?），没有则为 null。 */
    public final String query;
    public final Map<String, String> headers;

    Request(String method, String target, String path, String query, Map<String, String> headers) {
        this.method = method;
        this.target = target;
        this.path = path;
        this.query = query;
        this.headers = Collections.unmodifiableMap(headers);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean acceptsGzip() {
        String ae = header("accept-encoding");
        return ae != null && ae.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    @Override
    public String toString() {
        return method + " " + target;
    }
}
