package com.example.urlshortener.dto;

import java.time.Instant;
import java.util.List;

public record AnalyticsResponse(
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        Integer totalClicks,        // from Url.clickCount (PostgreSQL)
        List<ClickEventsDTO> clicks  // from ClickEvents collection (MongoDB)
) {}
