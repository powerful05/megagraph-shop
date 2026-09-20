package com.megagraph.shop.payment;

import com.megagraph.shop.shared.DomainException;
import org.springframework.http.HttpStatus;

public class CallbackException extends DomainException {
    public CallbackException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
