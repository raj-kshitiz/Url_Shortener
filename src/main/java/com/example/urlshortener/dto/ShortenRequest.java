package com.example.urlshortener.dto;

import java.time.Instant;

public record ShortenRequest(
        String originalUrl,
        String customAlias,
        Instant expiresAt
) {
}
