package com.example.urlshortener.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link UrlCacheService}.
 *
 * Note: this test uses a Mockito "mock" of RedisTemplate instead of a real Redis
 * server, so it runs anywhere (no Docker/Redis needed) and only checks OUR logic.
 * (The old RedisTests wrote key "personal:email" but read key "email", so it always
 * printed null and asserted nothing — that has been replaced by this file.)
 */
@ExtendWith(MockitoExtension.class)
class UrlCacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private UrlCacheService urlCacheService;

    @Test
    void getCachedUrl_returnsValue_whenKeyExists() {
        // given: Redis has a value stored under the prefixed key
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shorturl:abc123")).thenReturn("https://example.com");

        // when
        Optional<String> result = urlCacheService.getCachedUrl("abc123");

        // then
        assertThat(result).contains("https://example.com");
    }

    @Test
    void getCachedUrl_returnsEmpty_whenKeyMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("shorturl:missing")).thenReturn(null);

        Optional<String> result = urlCacheService.getCachedUrl("missing");

        assertThat(result).isEmpty();
    }

    @Test
    void isCached_isTrue_whenKeyPresent() {
        when(redisTemplate.hasKey("shorturl:abc123")).thenReturn(true);

        assertThat(urlCacheService.isCached("abc123")).isTrue();
    }

    @Test
    void isCached_isFalse_whenRedisReturnsNull() {
        // hasKey can return null; the service must treat that as "not cached"
        when(redisTemplate.hasKey("shorturl:abc123")).thenReturn(null);

        assertThat(urlCacheService.isCached("abc123")).isFalse();
    }

    @Test
    void cacheUrl_writesValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // expiresAt = null -> service should fall back to its default 24h TTL
        urlCacheService.cacheUrl("abc123", "https://example.com", null);

        // verify the value was written under the prefixed key with some TTL
        verify(valueOps).set(eq("shorturl:abc123"), eq("https://example.com"), any());
    }
}
