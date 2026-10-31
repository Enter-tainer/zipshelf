package moe.mgt.zipshelf;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import moe.mgt.zipshelf.data.Book;
import moe.mgt.zipshelf.http.HttpServer;
import moe.mgt.zipshelf.http.LibraryHandler;

/** 进程内唯一的 HTTP 服务实例，书架与服务共享。 */
public final class ServerCore {

    public static final int BASE_PORT = 8787;

    private static HttpServer server;
    private static int port = -1;
    private static volatile Map<String, LibraryHandler.Book> books = Collections.emptyMap();
    private static volatile moe.mgt.zipshelf.http.AssetSource assets;

    private ServerCore() {
    }

    /** App 自带资源（正文衬线字体）。服务启动前注入；为空时 /@/ 返回 404。 */
    public static void setAssets(moe.mgt.zipshelf.http.AssetSource source) {
        assets = source;
    }

    public static synchronized int ensureStarted() throws IOException {
        if (server != null && server.isRunning()) {
            return port;
        }
        HttpServer s = new HttpServer(new LibraryHandler(() -> books, true, assets), BASE_PORT);
        port = s.start();
        server = s;
        return port;
    }

    public static synchronized void stop() {
        HttpServer s = server;
        server = null;
        port = -1;
        if (s != null) {
            s.stop();
        }
    }

    public static int port() {
        return port;
    }

    public static boolean isRunning() {
        HttpServer s = server;
        return s != null && s.isRunning();
    }

    public static void setBooks(List<Book> list) {
        Map<String, LibraryHandler.Book> map = new LinkedHashMap<>();
        for (Book b : list) {
            map.put(b.id, b);
        }
        books = map;
    }

    public static Map<String, LibraryHandler.Book> books() {
        return books;
    }

    public static String bookUrl(String id) {
        int p = port;
        return p <= 0 ? null : "http://127.0.0.1:" + p + "/books/" + id + "/";
    }
}
