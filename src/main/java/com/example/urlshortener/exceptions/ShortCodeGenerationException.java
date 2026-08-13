package com.example.urlshortener.exceptions;

/**
 * Thrown when several random short codes in a row all collided with an existing one.
 * In a ~56 billion code keyspace this means the space is exhausted, not that we were
 * unlucky — so it is a real failure and falls through to the generic 500 handler,
 * which logs it. Retrying forever would turn a full keyspace into a hung request.
 */
public class ShortCodeGenerationException extends RuntimeException {
    public ShortCodeGenerationException(int attempts) {
        super("Could not generate an unused short code after " + attempts + " attempts");
    }
}
