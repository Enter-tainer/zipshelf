package moe.mgt.zipshelf.http;

import java.io.IOException;
import java.io.OutputStream;

/** 响应体。offset/len 用于 Range 请求。 */
public interface Body {

    /** 完整长度（经过 Content-Encoding 之后的字节数）。 */
    long length();

    /** 是否支持 Range（只有 identity 的静态字节流支持）。 */
    default boolean supportsRange() {
        return false;
    }

    /** 写 [offset, offset+len) 这段字节。 */
    void writeTo(OutputStream out, long offset, long len) throws IOException;
}
