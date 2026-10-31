package moe.mgt.zipshelf.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** URL 路径的解码与归一化。 */
public final class UrlPath {

    private UrlPath() {
    }

    /** 百分号解码（UTF-8）。'+' 在路径里保持原样。 */
    public static String decode(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 归一化：去掉 {@code .} 与重复斜杠，拒绝 {@code ..}。
     * 保留结尾斜杠（目录请求），保证结果以 {@code /} 开头。
     *
     * @throws IllegalArgumentException 路径试图越界
     */
    public static String normalize(String decoded) {
        if (decoded.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('/');
        boolean trailingSlash = decoded.endsWith("/");
        String[] parts = decoded.split("/");
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("路径包含 ..");
            }
            if (!first) {
                sb.append('/');
            }
            sb.append(part);
            first = false;
        }
        if (trailingSlash && sb.length() > 1) {
            sb.append('/');
        }
        return sb.toString();
    }
}
