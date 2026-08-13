package com.example.urlshortener.service;

import com.example.urlshortener.dto.AnalyticsResponse;
import com.example.urlshortener.dto.ClickEventsDTO;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exceptions.CustomAliasAlreadyTakenException;
import com.example.urlshortener.exceptions.ShortCodeGenerationException;
import com.example.urlshortener.exceptions.UrlExpiredException;
import com.example.urlshortener.exceptions.UrlNotFoundException;
import com.example.urlshortener.model.ClickEvents;
import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.ClickEventsRepository;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.utilities.Base62Encoding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
    private final ClickCounterService clickCounterService;

    /** Name of the unique index on url.short_code, from V1__create_url_table.sql. */
    private static final String SHORT_CODE_CONSTRAINT = "uk_url_short_code";

    /**
     * Draws before giving up on generating a free code. With ~56 billion codes, losing
     * five draws in a row means the keyspace is genuinely full, not that we were unlucky —
     * at which point failing loudly beats looping forever.
     */
    private static final int MAX_CODE_ATTEMPTS = 5;

    @Value("${app.base-url}")
    private String baseUrl;
    private ShortenResponse mapToShortenResponseDTO(Url url) {
        return new ShortenResponse(
                baseUrl + "/" + url.getShortCode(), //shortUrl constructed from baseUrl and shortCode
                url.getOriginalUrl(),
                url.getExpiresAt()
        );
    }

    /**
     * Creates a short URL.
     *
     * There is no "is this code free?" question that stays true long enough to act on:
     * between the SELECT and the INSERT another request can claim the same code. So the
     * unique index — the only thing that can decide this atomically — is the check, and
     * this method's job is to translate the constraint violation into the right answer:
     * a 409 for an alias the user asked for by name, a fresh code for one we generated.
     */
    public ShortenResponse addUrl(ShortenRequest request) {
        boolean isCustomAlias = request.customAlias() != null && !request.customAlias().isBlank();

        if (isCustomAlias) {
            String alias = request.customAlias();
            // Kept only so the ordinary "that name is taken" answer costs a SELECT rather
            // than a failed INSERT. It is not what makes this correct — the catch is.
            if (urlRepository.existsByShortCode(alias)) {
                throw new CustomAliasAlreadyTakenException(alias);
            }
            try {
                return persist(request, alias, true);
            } catch (DataIntegrityViolationException e) {
                if (!isShortCodeConflict(e)) {
                    throw e;   // some other constraint — not ours to reinterpret
                }
                // Someone claimed the alias between the check above and the insert.
                // Same answer as the check would have given, one moment later.
                throw new CustomAliasAlreadyTakenException(alias);
            }
        }

        // A generated code has no user expectation attached to it, so a collision is not
        // an error to report — it is a reason to draw again.
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return persist(request, generateShortCode(), false);
            } catch (DataIntegrityViolationException e) {
                if (!isShortCodeConflict(e)) {
                    throw e;
                }
                log.warn("Short code collision, retrying ({}/{})", attempt, MAX_CODE_ATTEMPTS);
            }
        }
        throw new ShortCodeGenerationException(MAX_CODE_ATTEMPTS);
    }

    private ShortenResponse persist(ShortenRequest request, String shortCode, boolean isCustomAlias) {
        Url url = Url.builder()
                .originalUrl(request.originalUrl())
                .customAlias(isCustomAlias)
                .expiresAt(request.expiresAt())
                .shortCode(shortCode)
                .build();

        // saveAndFlush, not save: the INSERT has to hit the database inside this call so
        // the violation surfaces here, where it can be retried, rather than at some later
        // commit point outside the loop.
        urlRepository.saveAndFlush(url);

        return mapToShortenResponseDTO(url);
    }

    /**
     * Codes are drawn at random from [62^5, 62^6) — always 6 characters, ~56 billion of
     * them. The old "loop until existsByShortCode says no" is gone: it cost a SELECT per
     * create to guard against a collision that the unique index catches anyway.
     */
    private String generateShortCode() {
        long min = (long) Math.pow(62, 5);
        long max = (long) Math.pow(62, 6);
        return Base62Encoding.encode(ThreadLocalRandom.current().nextLong(min, max));
    }

    /**
     * Was this a duplicate short code, or a different constraint entirely?
     * {@code original_url} is VARCHAR(2048), so an over-long URL also arrives as a
     * DataIntegrityViolationException — reporting that as "alias already taken" would be
     * a lie. Postgres names the offending constraint and Hibernate passes the name
     * through, so ask it instead of guessing.
     */
    private boolean isShortCodeConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && SHORT_CODE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
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
        clickCounterService.increment(shortCode);

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
                url.getClickCount() + clickCounterService.pendingFor(shortCode),
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
