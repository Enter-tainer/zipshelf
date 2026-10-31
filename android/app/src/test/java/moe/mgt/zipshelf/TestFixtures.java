package moe.mgt.zipshelf;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 从 classpath 取测试夹具并落成临时文件（ZipArchive 需要真实文件）。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static File zip(String name) throws IOException {
        try (InputStream in = TestFixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IOException("缺少夹具: " + name);
            }
            File tmp = File.createTempFile("zipshelf-" + name, ".zip");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    public static byte[] bytes(String name) throws IOException {
        try (InputStream in = TestFixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IOException("缺少夹具: " + name);
            }
            return in.readAllBytes();
        }
    }
}
