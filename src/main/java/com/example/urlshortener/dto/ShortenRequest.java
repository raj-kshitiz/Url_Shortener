package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record ShortenRequest(
        @NotBlank
        @org.hibernate.validator.constraints.URL
        String originalUrl,
        String customAlias,
        Instant expiresAt
) {
}
