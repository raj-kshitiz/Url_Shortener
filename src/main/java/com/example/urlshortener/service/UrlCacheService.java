package com.example.urlshortener.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UrlCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String KEY_PREFIX = "shorturl:";
    private static final Duration MAX_TTL = Duration.ofHours(24);

    // Helper function to calculate TTL -> time to live for the short code
    // Basically it checks if the expiration time is greater than the MAX_TTL, if yes then it returns the max expiration time
    // otherwise returns the set expiry time
    private Duration calculateTtl(Instant expiresAt) {
        if (expiresAt == null) {
            return MAX_TTL; // no expiry set, cache for default 24h
        }
        Duration timeUntilExpiry = Duration.between(Instant.now(), expiresAt);
        return timeUntilExpiry.compareTo(MAX_TTL) < 0 ? timeUntilExpiry : MAX_TTL;
    }

    // Cache a short URL mapping
    public void cacheUrl(String shortCode, String originalUrl, Instant expiresAt) {
        Duration ttl = calculateTtl(expiresAt);
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        ops.set(KEY_PREFIX + shortCode, originalUrl, ttl);
    }

    // Get from cache
    public Optional<String> getCachedUrl(String shortCode) {
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        String value = ops.get(KEY_PREFIX + shortCode);
        return Optional.ofNullable(value);
    }

    // Delete from cache (when URL is updated or deleted)
    public void evictUrl(String shortCode) {
        redisTemplate.delete(KEY_PREFIX + shortCode);
    }

    // Check if it's cached
    public boolean isCached(String shortCode) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + shortCode));
    }

    // Get TTL remaining
    public long getTimeToLive(String shortCode) {
        return redisTemplate.getExpire(KEY_PREFIX + shortCode, TimeUnit.SECONDS);
    }
}
