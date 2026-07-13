package com.example.urlshortener.exceptions;

public class CustomAliasAlreadyTakenException extends RuntimeException{
    public CustomAliasAlreadyTakenException(String customAlias) {
        super("Custom alias already taken: " + customAlias);
    }
}
