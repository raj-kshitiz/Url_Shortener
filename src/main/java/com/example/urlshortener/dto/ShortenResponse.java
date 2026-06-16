package com.example.urlshortener.dto;

import java.time.Instant;

public record ShortenResponse(
        String shortUrl,
        String originalUrl,
        Instant expiresAt

) {
}
