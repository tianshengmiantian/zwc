package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.ApiIntegrationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
@SuppressWarnings("unused") // Exception handlers are invoked reflectively by Spring MVC.
public class ApiExceptionHandler {

    @ExceptionHandler(ApiIntegrationException.class)
    public ResponseEntity<Map<String, Object>> integration(ApiIntegrationException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", exception.getMessage()
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(
            MissingServletRequestParameterException exception
    ) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "缺少参数：" + exception.getParameterName()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
                "success", false,
                "error", "上传文件过大，本地上传最多支持 7 MB；大文件请使用 URL 接口"
        ));
    }
}
