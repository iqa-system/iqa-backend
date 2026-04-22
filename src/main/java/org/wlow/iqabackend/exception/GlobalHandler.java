package org.wlow.iqabackend.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.wlow.iqabackend.data.dto.Response;

import java.util.Arrays;

/**
 * 全局异常处理器. 处理一些常见的异常
 */
@Slf4j
@RestControllerAdvice
public class GlobalHandler {
    /**
     * 处理请求参数缺少/未传递的异常 <br>
     * {@link ResponseStatus}可以指定响应的HTTP状态码. 不加时默认为200
     * 但是加了又不指定value时会是500
     */
    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Response<String> handleMissingServletRequestParameterException(MissingServletRequestParameterException e){
        log.warn("缺少请求参数: {} {}", e.getParameterType(), e.getParameterName());
        return Response.failure(400, "缺少请求参数: " + e.getParameterType() + " " + e.getParameterName());
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Response<String> handleIllegalArgumentException(IllegalArgumentException e){
        log.warn("非法参数: {}", e.getMessage());
        return Response.failure(400, "非法参数: " + e.getMessage());
    }

    /**
     * 处理参数校验未通过异常 <br>
     * 如果是Controller中直接接收参数校验不通过, 抛出的是HandlerMethodValidationException异常 <br>
     * 如果是DTO类接收参数校验不通过, 抛出的是MethodArgumentNotValidException异常
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    public Response<String> handleMethodArgumentNotValidException(Exception e){
        String message;
        if (e instanceof MethodArgumentNotValidException e1) {
            message = Arrays.toString(e1.getDetailMessageArguments());
        } else if (e instanceof HandlerMethodValidationException e2) {
            message = Arrays.toString(e2.getDetailMessageArguments());
        } else {
            message = e.getMessage();
        }
        log.warn("参数校验未通过: {}", message);
        return Response.failure(400, "参数校验未通过: " + message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Response<String> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e){
        log.warn("不支持的请求方法: {}", e.getMethod());
        return Response.failure(405, "不支持的请求方法: " + e.getMethod());
    }

    @ExceptionHandler(MyBatisSystemException.class)
    public Response<String> handleMyBatisSystemException(MyBatisSystemException e){
        log.error("数据库异常: {}", e.getMessage());
        return Response.failure(500, "数据库异常: " + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Response<String> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e){
        String parameterName = e.getName();
        String parameterType = e.getParameter().getParameterType().getName();
        log.warn("参数类型不匹配: 参数 {} 应当为 {} 类型", parameterName, parameterType);
        return Response.failure(400, "参数类型不匹配: 参数 " + parameterName + " 应当为 " + parameterType + " 类型");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Response<String> handleConstraintViolationException(ConstraintViolationException e){
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .reduce((s1, s2) -> s1 + "; " + s2)
                .orElse(e.getMessage());
        log.warn("参数不符合规范: {}", message);
        return Response.failure(400, "参数不符合规范: " + message);
    }

    /**
     * 处理JSON请求体结构错误或类型不匹配等反序列化异常.
     * DELETE /assess/history 这样的JSON接口会走到这里, 统一返回400而不是500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Response<String> handleHttpMessageNotReadableException(HttpMessageNotReadableException e){
        log.warn("请求体格式不正确: {}", e.getMessage());
        return Response.failure(400, "请求体格式不正确");
    }

    /**
     * 处理远程模型服务调用异常.
     */
    @ExceptionHandler(RestClientException.class)
    public Response<String> handleRestClientException(RestClientException e){
        log.error("远程服务调用失败: {}", e.getMessage());
        return Response.failure(502, "远程服务调用失败: " + e.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AdminAccessDeniedException.class)
    public Response<String> handleAdminAccessDeniedException(AdminAccessDeniedException e) {
        log.warn("管理员权限不足: {}", e.getMessage());
        return Response.failure(403, e.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(FileAccessDeniedException.class)
    public Response<String> handleFileAccessDeniedException(FileAccessDeniedException e) {
        log.warn("文件访问被拒绝: {}", e.getMessage());
        return Response.failure(403, e.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(FileResourceNotFoundException.class)
    public Response<String> handleFileResourceNotFoundException(FileResourceNotFoundException e) {
        log.warn("文件资源不存在: {}", e.getMessage());
        return Response.failure(404, e.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AssessRecordAccessDeniedException.class)
    public Response<String> handleAssessRecordAccessDeniedException(AssessRecordAccessDeniedException e) {
        log.warn("评估历史记录访问被拒绝: {}", e.getMessage());
        return Response.failure(403, e.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(ExpertAccessDeniedException.class)
    public Response<String> handleExpertAccessDeniedException(ExpertAccessDeniedException e) {
        log.warn("专家主观评分权限不足: {}", e.getMessage());
        return Response.failure(403, e.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(AssessRecordNotFoundException.class)
    public Response<String> handleAssessRecordNotFoundException(AssessRecordNotFoundException e) {
        log.warn("评估历史记录不存在: {}", e.getMessage());
        return Response.failure(404, e.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(UserNotFoundException.class)
    public Response<String> handleUserNotFoundException(UserNotFoundException e) {
        log.warn("用户不存在: {}", e.getMessage());
        return Response.failure(404, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Response<String> handleException(Exception e){
        log.error("服务端异常: {} >> {}", e.getClass(), e.getMessage());
        return Response.error("服务端异常: " + e.getMessage());
    }
}
