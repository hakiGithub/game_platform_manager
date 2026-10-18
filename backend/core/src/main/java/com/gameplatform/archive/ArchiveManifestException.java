package com.gameplatform.archive;

/** 归档清单校验失败。 */
public class ArchiveManifestException extends RuntimeException {

    private final String failureCode;

    public ArchiveManifestException(String message) {
        this(null, message, null);
    }

    public ArchiveManifestException(String failureCode, String message) {
        this(failureCode, message, null);
    }

    public ArchiveManifestException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public ArchiveManifestException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
