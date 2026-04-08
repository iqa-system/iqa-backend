package org.wlow.iqabackend.exception;

/**
 * 评估历史记录越权访问/删除异常.
 */
public class AssessRecordAccessDeniedException extends RuntimeException {
    public AssessRecordAccessDeniedException(String message) {
        super(message);
    }
}
