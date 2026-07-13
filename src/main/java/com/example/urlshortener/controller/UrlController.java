package com.example.urlshortener.controller;

import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping("/api/urls")
    public ResponseEntity<ShortenResponse> addUrl(
            @RequestBody ShortenRequest request
    ) {
        return new ResponseEntity<>(
                urlService.addUrl(request),
                HttpStatus.CREATED
        );
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> getOriginalUrl(
            @PathVariable String shortCode,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ipAddress,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestHeader(value = "Referer", required = false) String referer
    ) {
        String originalUrl = urlService.getOriginalUrl(shortCode, ipAddress, userAgent, referer);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }

    @GetMapping("/api/urls/{shortCode}/analytics")
    public ResponseEntity<AnalyticsResponse> getAnalytics(
            @PathVariable String shortCode
    ) {
        return new ResponseEntity<>(
                urlService.getAnalytics(shortCode),
                HttpStatus.OK
        );
    }
}
