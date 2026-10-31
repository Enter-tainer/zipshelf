package moe.mgt.zipshelf.http;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 扩展名 → Content-Type。够用即可。 */
public final class Mime {

    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("htm", "text/html; charset=utf-8");
        TYPES.put("xhtml", "application/xhtml+xml; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("mjs", "text/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("map", "application/json; charset=utf-8");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("md", "text/markdown; charset=utf-8");
        TYPES.put("xml", "application/xml; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
        TYPES.put("webp", "image/webp");
        TYPES.put("avif", "image/avif");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff", "font/woff");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("ttf", "font/ttf");
        TYPES.put("otf", "font/otf");
        TYPES.put("pdf", "application/pdf");
        TYPES.put("mp4", "video/mp4");
        TYPES.put("webm", "video/webm");
        TYPES.put("mp3", "audio/mpeg");
        TYPES.put("wasm", "application/wasm");
    }

    private Mime() {
    }

    public static String forName(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return "application/octet-stream";
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String t = TYPES.get(ext);
        return t != null ? t : "application/octet-stream";
    }

    public static boolean isText(String contentType) {
        return contentType.startsWith("text/") || contentType.contains("svg") || contentType.contains("json");
    }
}
