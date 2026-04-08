package org.wlow.iqabackend.exception;

/**
 * 文件资源不存在异常.
 */
public class FileResourceNotFoundException extends RuntimeException {
    public FileResourceNotFoundException(String message) {
        super(message);
    }
}
