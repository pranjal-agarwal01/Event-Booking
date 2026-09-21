package com.booking.common;

/** Maps to HTTP 401 - bad credentials or an unusable token. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
