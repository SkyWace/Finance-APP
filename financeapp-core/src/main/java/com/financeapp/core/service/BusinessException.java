package com.financeapp.core.service;

/** Operation refusee pour une raison metier ; le message est destine a l'utilisateur. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
