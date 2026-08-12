package com.example.urlshortener.service;

import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.dto.ClickEventsDTO;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exceptions.CustomAliasAlreadyTakenException;
import com.example.urlshortener.exceptions.UrlExpiredException;
import com.example.urlshortener.exceptions.UrlNotFoundException;
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
    private final ClickTrackingService clickTrackingService;   // for queuing click events to be processed

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
        boolean isCustomAlias = request.customAlias() != null && !request.customAlias().isBlank();
        String shortCode;
        if (isCustomAlias) {
            // user provided their own alias
            if (urlRepository.existsByShortCode(request.customAlias())) {
                throw new CustomAliasAlreadyTakenException(request.customAlias());
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
                .customAlias(isCustomAlias)
                .expiresAt(request.expiresAt())
                .shortCode(shortCode)
                .build();

        urlRepository.save(url);

        return mapToShortenResponseDTO(url);
    }

//    @Transactional --> gone because we have queued the mongo write using async
    public String getOriginalUrl(String shortCode, String ipAddress, String userAgent, String referer) {
        String originalUrl;
        if(urlCacheService.isCached(shortCode)) {
            log.info("URL found in cache: {}", shortCode);
            originalUrl = urlCacheService.getCachedUrl(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

        } else {
            Url url = urlRepository.findByShortCode(shortCode)
                    .orElseThrow(() -> new UrlNotFoundException(shortCode));

            if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now())) {
                throw new UrlExpiredException(shortCode);
            }
            originalUrl = url.getOriginalUrl();
            urlCacheService.cacheUrl(shortCode, url.getOriginalUrl(), url.getExpiresAt());
        }
        urlRepository.incrementClickCount(shortCode);

        // was: ClickEvents.builder()…  clickEventsRepository.save(clickEvents);
        clickTrackingService.recordClick(shortCode, Instant.now(), ipAddress, userAgent, referer);

        return originalUrl;
    }

    public AnalyticsResponse getAnalytics(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

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
