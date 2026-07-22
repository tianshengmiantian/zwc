package com.westart.ai.westart.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
