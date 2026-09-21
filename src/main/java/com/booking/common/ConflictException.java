package com.booking.common;

/** Maps to HTTP 409 - the request was valid but conflicts with current state. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
