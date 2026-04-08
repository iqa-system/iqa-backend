package org.wlow.iqabackend.exception;

/**
 * 评估历史记录不存在异常.
 */
public class AssessRecordNotFoundException extends RuntimeException {
    public AssessRecordNotFoundException(String message) {
        super(message);
    }
}
