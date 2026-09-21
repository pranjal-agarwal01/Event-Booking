package com.booking.common;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " with id " + id + " was not found");
    }
}
