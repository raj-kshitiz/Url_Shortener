package com.example.urlshortener.exceptions;

public class UrlExpiredException extends RuntimeException{
    public UrlExpiredException(String shortCode){
        super("URL has expired : " + shortCode);
    }
}
