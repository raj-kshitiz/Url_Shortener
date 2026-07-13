package com.example.urlshortener.dto;

import java.time.Instant;

public record ClickEventsDTO(
        Instant timestamp,
        String ipAddress,
        String userAgent,
        String referer
//        String country,
//        String city
) {}
