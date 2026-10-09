package com.example.booking_service.exception;

public class TrainNotAvailableException extends RuntimeException {
    public TrainNotAvailableException(String message) {
        super(message);
    }

    public TrainNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
