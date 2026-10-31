package moe.mgt.zipshelf.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一个待发送的 HTTP 响应。 */
public final class Response {

    public int status = 200;
    public String contentType = "application/octet-stream";
    public String contentEncoding;
    public String etag;
    public String cacheControl = "public, max-age=31536000, immutable";
    public Body body;
    public final Map<String, String> extraHeaders = new LinkedHashMap<>();

    public static Response text(int status, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Response r = new Response();
        r.status = status;
        r.contentType = "text/plain; charset=utf-8";
        r.cacheControl = "no-store";
        r.body = new ByteBody(bytes);
        return r;
    }

    public static Response html(int status, String html) {
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Response r = new Response();
        r.status = status;
        r.contentType = "text/html; charset=utf-8";
        r.cacheControl = "no-store";
        r.body = new ByteBody(bytes);
        return r;
    }

    public static Response redirect(String location) {
        Response r = new Response();
        r.status = 302;
        r.cacheControl = "no-store";
        r.extraHeaders.put("Location", location);
        return r;
    }
}
