package moe.mgt.zipshelf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import moe.mgt.zipshelf.TestFixtures;
import moe.mgt.zipshelf.zip.ZipArchive;

public class BookStoreDetectPrefixTest {

    @Test
    public void rootIndexMeansEmptyPrefix() throws Exception {
        ZipArchive archive = ZipArchive.open(TestFixtures.zip("fixture_deflate.zip"));
        assertEquals("", BookStore.detectPrefix(archive));
    }

    @Test
    public void singleTopLevelDirectoryIsDetected() throws Exception {
        ZipArchive archive = ZipArchive.open(TestFixtures.zip("fixture_nested.zip"));
        assertEquals("book/", BookStore.detectPrefix(archive));
        assertNotNull(archive.get("book/index.html"));
    }

    @Test
    public void missingIndexStillReturnsEmptyPrefix() throws Exception {
        ZipArchive archive = ZipArchive.open(TestFixtures.zip("fixture_noidx.zip"));
        assertEquals("", BookStore.detectPrefix(archive));
    }
}
