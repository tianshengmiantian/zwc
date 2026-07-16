package com.westart.ai.westart.service;

public class ApiIntegrationException extends RuntimeException {

    public ApiIntegrationException(String message) {
        super(message);
    }

    public ApiIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
