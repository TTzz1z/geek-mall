package com.geekmall.common.web;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ServiceErrorHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException exception, HttpServletRequest request) {
        HttpStatus status = exception.code().contains("503") ? HttpStatus.SERVICE_UNAVAILABLE
                : exception.code().contains("413") ? HttpStatus.PAYLOAD_TOO_LARGE
                : exception.code().contains("404") ? HttpStatus.NOT_FOUND
                : exception.code().contains("403") ? HttpStatus.FORBIDDEN
                : exception.code().contains("409") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.failure(exception.code(), exception.getMessage(), id(request)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ApiResponse<Void>> validation(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("CM40001", "请求参数校验失败", id(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.failure("PD41302", "上传文件不能超过10MB；商品封面不能超过5MB", id(request)));
    }

    private static String id(HttpServletRequest request) { return String.valueOf(request.getAttribute("requestId")); }
}
