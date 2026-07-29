package com.example.urlshortener.exceptions;

public class UrlNotFoundException extends RuntimeException{
    public UrlNotFoundException(String shortCode) {
        super("Short code not found: " + shortCode);
    }
}
