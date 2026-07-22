package com.westart.ai.westart.common.exception;

public class ApiIntegrationException extends RuntimeException {

    public ApiIntegrationException(String message) {
        super(message);
    }

    public ApiIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
