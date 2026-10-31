package moe.mgt.zipshelf.data;

import moe.mgt.zipshelf.zip.ZipArchive;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书架存储：{@code filesDir/books/<id>/book.zip + meta.properties}。
 *
 * 用 properties 而不是 JSON，是因为它不依赖 Android 的 org.json，纯 JVM 单测也能跑。
 */
public final class BookStore {

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_PROP = Pattern.compile(
            "property\\s*=\\s*[\"']og:image[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ATTR = Pattern.compile(
            "content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final String[] COVER_NAMES = {"cover.png", "cover.jpg", "cover.jpeg", "cover.webp"};
    private static final int TITLE_HEAD_BYTES = 64 * 1024;

    private final File root;

    public BookStore(File filesDir) {
        this.root = new File(filesDir, "books");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public File root() {
        return root;
    }

    // ------------------------------------------------------------- list

    public List<Book> list() {
        List<Book> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) {
                continue;
            }
            File zip = new File(dir, "book.zip");
            if (!zip.isFile()) {
                continue;
            }
            Book b = new Book(dir.getName(), zip);
            readMeta(b, new File(dir, "meta.properties"));
            out.add(b);
        }
        out.sort(Comparator.comparingLong((Book b) -> Math.max(b.lastOpenedAt, b.importedAt)).reversed());
        return out;
    }

    public Book get(String id) {
        for (Book b : list()) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- import

    public Book importFile(File src, String displayName) throws IOException, ImportException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 64 * 1024)) {
            return importStream(in, displayName);
        }
    }

    public Book importStream(InputStream in, String displayName) throws IOException, ImportException {
        File tmp = new File(root, ".import-" + UUID.randomUUID() + ".zip");
        long copied = 0;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                copied += r;
            }
        }
        if (copied == 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new ImportException("文件是空的");
        }

        ZipArchive archive;
        try {
            archive = ZipArchive.open(tmp);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new ImportException("不是有效的 zip：" + e.getMessage(), e);
        }

        String prefix = detectPrefix(archive);
        ZipArchive.Entry index = archive.getIgnoreCase(prefix + "index.html");
        if (index == null) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new ImportException("zip 里找不到 index.html，不是网站包");
        }

        String title = extractTitle(archive, index);
        if (title == null || title.trim().isEmpty()) {
            title = displayName != null ? displayName : "未命名";
        }

        String id = UUID.randomUUID().toString();
        File dir = new File(root, id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, "book.zip");
        if (!tmp.renameTo(dest)) {
            // 跨卷或占用：退回复制
            try (InputStream src = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = src.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }

        Book book = new Book(id, dest);
        book.title = title;
        book.entryPrefix = prefix;
        book.coverEntry = detectCover(archive, index, prefix);
        book.sizeBytes = dest.length();
        book.fileCount = archive.size();
        book.importedAt = System.currentTimeMillis();
        book.lastOpenedAt = 0;
        writeMeta(book, new File(dir, "meta.properties"));
        return book;
    }

    public void delete(Book book) {
        book.close();
        deleteRecursively(new File(root, book.id));
    }

    public void rename(Book book, String newTitle) throws IOException {
        book.title = newTitle == null || newTitle.trim().isEmpty() ? "未命名" : newTitle.trim();
        writeMeta(book, new File(new File(root, book.id), "meta.properties"));
    }

    public void touch(Book book) {
        book.lastOpenedAt = System.currentTimeMillis();
        try {
            writeMeta(book, new File(new File(root, book.id), "meta.properties"));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------- meta

    private void readMeta(Book b, File metaFile) {
        if (!metaFile.isFile()) {
            b.title = b.id;
            return;
        }
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(metaFile), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            b.title = b.id;
            return;
        }
        b.title = p.getProperty("title", b.id);
        b.entryPrefix = p.getProperty("prefix", "");
        String cover = p.getProperty("cover", "");
        b.coverEntry = cover.isEmpty() ? null : cover;
        b.sizeBytes = parseLong(p.getProperty("size"));
        b.fileCount = (int) parseLong(p.getProperty("count"));
        b.importedAt = parseLong(p.getProperty("imported"));
        b.lastOpenedAt = parseLong(p.getProperty("opened"));
    }

    private void writeMeta(Book b, File metaFile) throws IOException {
        Properties p = new Properties();
        p.setProperty("title", b.title == null ? "" : b.title);
        p.setProperty("prefix", b.entryPrefix == null ? "" : b.entryPrefix);
        p.setProperty("cover", b.coverEntry == null ? "" : b.coverEntry);
        p.setProperty("size", Long.toString(b.sizeBytes));
        p.setProperty("count", Integer.toString(b.fileCount));
        p.setProperty("imported", Long.toString(b.importedAt));
        p.setProperty("opened", Long.toString(b.lastOpenedAt));
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(metaFile), StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- detect

    /**
     * 网站根目录前缀：根目录有 index.html 就是 ""；否则若整个站被包在唯一一层目录里，
     * 用那层目录（常见于「右键压缩文件夹」）。
     */
    static String detectPrefix(ZipArchive archive) {
        if (archive.getIgnoreCase("index.html") != null) {
            return "";
        }
        Set<String> topDirs = new LinkedHashSet<>();
        for (ZipArchive.Entry e : archive.entries()) {
            String n = e.name;
            int slash = n.indexOf('/');
            if (slash <= 0) {
                return "";
            }
            topDirs.add(n.substring(0, slash));
            if (topDirs.size() > 1) {
                return "";
            }
        }
        for (String dir : topDirs) {
            if (archive.getIgnoreCase(dir + "/index.html") != null) {
                return dir + "/";
            }
        }
        return "";
    }

    private static String extractTitle(ZipArchive archive, ZipArchive.Entry index) {
        try {
            String head = new String(archive.readHead(index, TITLE_HEAD_BYTES), StandardCharsets.UTF_8);
            Matcher m = TITLE.matcher(head);
            if (m.find()) {
                return unescape(m.group(1)).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** 封面：优先根目录的 cover.*，其次 og:image 指向的条目。 */
    private static String detectCover(ZipArchive archive, ZipArchive.Entry index, String prefix) {
        for (String name : COVER_NAMES) {
            ZipArchive.Entry e = archive.getIgnoreCase(prefix + name);
            if (e != null) {
                return e.name;
            }
        }
        String og = extractOgImage(archive, index);
        if (og != null) {
            String path = og.trim();
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            ZipArchive.Entry e = archive.getIgnoreCase(prefix + path);
            if (e != null) {
                return e.name;
            }
        }
        return null;
    }

    private static String extractOgImage(ZipArchive archive, ZipArchive.Entry index) {
        try {
            String head = new String(archive.readHead(index, TITLE_HEAD_BYTES), StandardCharsets.UTF_8);
            Matcher tags = META_TAG.matcher(head);
            while (tags.find()) {
                String tag = tags.group();
                if (!OG_IMAGE_PROP.matcher(tag).find()) {
                    continue;
                }
                Matcher content = CONTENT_ATTR.matcher(tag);
                if (content.find()) {
                    return content.group(1);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
