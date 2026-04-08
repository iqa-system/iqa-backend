package org.wlow.iqabackend.exception;

/**
 * 文件访问越权异常.
 */
public class FileAccessDeniedException extends RuntimeException {
    public FileAccessDeniedException(String message) {
        super(message);
    }
}
