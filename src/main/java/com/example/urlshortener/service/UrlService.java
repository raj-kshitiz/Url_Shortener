package com.example.urlshortener.service;

import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.dto.ClickEventsDTO;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exceptions.ShortUrlNotFoundException;
import com.example.urlshortener.model.ClickEvents;
import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.ClickEventsRepository;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.utilities.Base62Encoding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {
    private final UrlRepository urlRepository;
    private final UrlCacheService urlCacheService;
    private final ClickEventsRepository clickEventsRepository;

    @Value("${app.base-url}")
    private String baseUrl;
    private ShortenResponse mapToShortenResponseDTO(Url url) {
        return new ShortenResponse(
                baseUrl + "/" + url.getShortCode(), //shortUrl constructed from baseUrl and shortCode
                url.getOriginalUrl(),
                url.getExpiresAt()
        );
    }

    public ShortenResponse addUrl(ShortenRequest request) {
        String shortCode;
        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            // user provided their own alias
            if (urlRepository.existsByShortCode(request.customAlias())) {
                // throw new CustomAliasAlreadyTakenException(request.customAlias());
                throw new RuntimeException("Custom alias already taken: " + request.customAlias()); // -> Placeholder only
            }
            shortCode = request.customAlias();
        } else {
            // auto generate
            long min = (long) Math.pow(62, 5);
            long max = (long) Math.pow(62, 6);
            do {
                long randomValue = ThreadLocalRandom.current().nextLong(min, max);
                shortCode = Base62Encoding.encode(randomValue);
            } while (urlRepository.existsByShortCode(shortCode));
        }

        Url url = Url.builder()
                .originalUrl(request.originalUrl())
                .customAlias(request.customAlias() != null)
                .expiresAt(request.expiresAt())
                .shortCode(shortCode)
                .build();

        urlRepository.save(url);

        return mapToShortenResponseDTO(url);
    }

    @Transactional
    public String getOriginalUrl(String shortCode, String ipAddress, String userAgent, String referer) {
        String originalUrl;
        if(urlCacheService.isCached(shortCode)) {
            log.info("URL found in cache: {}", shortCode);
            originalUrl = urlCacheService.getCachedUrl(shortCode)
                    .orElseThrow(() -> new ShortUrlNotFoundException("Cached URL missing for: " + shortCode));

        } else {
            Url url = urlRepository.findByShortCode(shortCode)
                    .orElseThrow(() -> new ShortUrlNotFoundException("No URL found for short code: " + shortCode));

            if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
                throw new ShortUrlNotFoundException("Short URL has expired: " + shortCode);
            }
            originalUrl = url.getOriginalUrl();
            urlCacheService.cacheUrl(shortCode, url.getOriginalUrl(), url.getExpiresAt());
        }
        urlRepository.incrementClickCount(shortCode);

        ClickEvents clickEvents = ClickEvents.builder()
                .shortCode(shortCode)
                .timestamp(Instant.now())
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .referer(referer)
                .build();
        clickEventsRepository.save(clickEvents);

        return originalUrl;
    }

    public AnalyticsResponse getAnalytics(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException("No URL found for short code: " + shortCode));

        List<ClickEvents> clickEvents = clickEventsRepository.findByShortCode(shortCode);

        List<ClickEventsDTO> clickEventsDTOs = clickEvents.stream()
                .map(this::mapToClickEventsDTO)
                .toList();

        return new AnalyticsResponse(
                baseUrl + "/" + url.getShortCode(),
                url.getOriginalUrl(),
                url.getCreatedAt(),
                url.getExpiresAt(),
                url.getClickCount(),
                clickEventsDTOs
        );
    }

    private ClickEventsDTO mapToClickEventsDTO(ClickEvents clickEvents) {
        return new ClickEventsDTO(
                clickEvents.getTimestamp(),
                clickEvents.getIpAddress(),
                clickEvents.getUserAgent(),
                clickEvents.getReferer()
        );
    }

}
