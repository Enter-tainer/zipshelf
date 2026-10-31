package moe.mgt.zipshelf.data;

import moe.mgt.zipshelf.http.LibraryHandler;
import moe.mgt.zipshelf.zip.ZipArchive;

import java.io.File;
import java.io.IOException;

/** 书架里的一本书 = 一个 zip + 一份元数据。zip 原样保留，不解压。 */
public final class Book implements LibraryHandler.Book {

    public final String id;
    public final File zip;
    public String title;
    /** 网站根目录在 zip 内的前缀，"" 或 "somedir/"。 */
    public String entryPrefix = "";
    /** 封面在 zip 内的条目名，可能为 null。 */
    public String coverEntry;
    public long sizeBytes;
    public int fileCount;
    public long importedAt;
    public long lastOpenedAt;

    private volatile ZipArchive archive;

    Book(String id, File zip) {
        this.id = id;
        this.zip = zip;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return title == null || title.isEmpty() ? id : title;
    }

    @Override
    public String entryPrefix() {
        return entryPrefix == null ? "" : entryPrefix;
    }

    @Override
    public ZipArchive archive() {
        ZipArchive a = archive;
        if (a == null) {
            synchronized (this) {
                a = archive;
                if (a == null) {
                    try {
                        a = ZipArchive.open(zip);
                    } catch (IOException e) {
                        throw new IllegalStateException("打不开 zip: " + zip, e);
                    }
                    archive = a;
                }
            }
        }
        return a;
    }

    public void close() {
        synchronized (this) {
            archive = null;
        }
    }

    @Override
    public String toString() {
        return title() + " (" + id + ")";
    }
}
