package com.megagraph.shop.shared;

import org.springframework.http.HttpStatus;

public class ValidationException extends DomainException {
    public ValidationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}

