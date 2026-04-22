package org.wlow.iqabackend.exception;

/**
 * 专家主观评分权限不足异常.
 * 只有EXPERT角色才能提交或修改expertScore.
 */
public class ExpertAccessDeniedException extends RuntimeException {
    public ExpertAccessDeniedException(String message) {
        super(message);
    }
}
