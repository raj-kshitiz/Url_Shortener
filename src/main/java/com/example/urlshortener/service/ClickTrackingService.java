package com.example.urlshortener.service;

import com.example.urlshortener.model.ClickEvents;
import com.example.urlshortener.repository.ClickEventsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records click events off the request thread. A separate bean from UrlService
 * on purpose: @Async only engages across a proxy boundary, so an @Async method
 * called from within UrlService would silently run synchronously.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickTrackingService {

    private final ClickEventsRepository clickEventsRepository;

    /**
     * Fire-and-forget. Returns to the caller before Mongo is touched.
     *
     * occurredAt is a parameter rather than an Instant.now() inside this method:
     * calling it here would stamp the event with the time the worker drained the
     * queue, not the time of the click — skewing timestamps precisely when
     * you're backed up and the data matters most.
     */

    @Async("clickEventExecutor")
    public void recordClick(String shortCode, Instant occurredAt,
                            String ipAddress, String userAgent, String referer) {
        clickEventsRepository.save(
                ClickEvents.builder()
                        .shortCode(shortCode)
                        .timestamp(occurredAt)
                        .ipAddress(ipAddress)
                        .userAgent(userAgent)
                        .referer(referer)
                        .build()
        );
        log.debug("Recorded click for {} on {}", shortCode, Thread.currentThread().getName());
    }
}