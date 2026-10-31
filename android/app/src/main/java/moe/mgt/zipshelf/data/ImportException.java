package moe.mgt.zipshelf.data;

/** 导入失败，message 直接展示给用户。 */
public final class ImportException extends Exception {
    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
