package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.exceptions.CustomAliasAlreadyTakenException;
import com.example.urlshortener.exceptions.ShortCodeGenerationException;
import com.example.urlshortener.model.Url;
import com.example.urlshortener.repository.ClickEventsRepository;
import com.example.urlshortener.repository.UrlRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code addUrl} does when the INSERT loses the race — the case the old
 * exists-then-save could only reach by returning a 500.
 *
 * The interleaving itself can't be staged from a unit test, so these tests stage its
 * outcome: the repository throws exactly what Postgres throws when two inserts land on
 * {@code uk_url_short_code}, and the tests assert on the answer the caller gets.
 * {@link com.example.urlshortener.service.UrlCacheServiceTest} takes the same approach
 * for Redis. The real concurrent case is covered end-to-end by the k6 script in
 * {@code benchmarks/}.
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;
    @Mock
    private UrlCacheService urlCacheService;
    @Mock
    private ClickEventsRepository clickEventsRepository;
    @Mock
    private ClickTrackingService clickTrackingService;
    @Mock
    private ClickCounterService clickCounterService;

    @InjectMocks
    private UrlService urlService;

    @BeforeEach
    void setBaseUrl() {
        // Normally injected by @Value; there is no Spring context in a unit test.
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
    }

    /** What Postgres raises when an INSERT collides on the short_code unique index. */
    private static DataIntegrityViolationException shortCodeConflict() {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException(
                        "duplicate key value violates unique constraint \"uk_url_short_code\"",
                        new SQLException("duplicate key", "23505"),
                        "uk_url_short_code"));
    }

    /** A different constraint on the same table — must not be reported as a taken alias. */
    private static DataIntegrityViolationException otherConflict() {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException(
                        "value too long for type character varying(2048)",
                        new SQLException("value too long", "22001"),
                        "url_original_url_check"));
    }

    private static ShortenRequest request(String alias) {
        return new ShortenRequest("https://example.com", alias, null);
    }

    @Test
    void customAlias_alreadyTaken_isRejectedWithoutInserting() {
        when(urlRepository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> urlService.addUrl(request("taken")))
                .isInstanceOf(CustomAliasAlreadyTakenException.class);

        verify(urlRepository, never()).saveAndFlush(any());
    }

    @Test
    void customAlias_claimedBetweenCheckAndInsert_stillGets409() {
        // The race, staged: the pre-check says free, the INSERT says otherwise.
        when(urlRepository.existsByShortCode("racy")).thenReturn(false);
        when(urlRepository.saveAndFlush(any(Url.class))).thenThrow(shortCodeConflict());

        assertThatThrownBy(() -> urlService.addUrl(request("racy")))
                .isInstanceOf(CustomAliasAlreadyTakenException.class)
                .hasMessageContaining("racy");
    }

    @Test
    void customAlias_isNotRetriedUnderAnotherName() {
        when(urlRepository.existsByShortCode("mine")).thenReturn(false);
        when(urlRepository.saveAndFlush(any(Url.class))).thenThrow(shortCodeConflict());

        assertThatThrownBy(() -> urlService.addUrl(request("mine")))
                .isInstanceOf(CustomAliasAlreadyTakenException.class);

        // The user asked for this exact code. Silently handing back a different one
        // would be worse than the conflict.
        verify(urlRepository, times(1)).saveAndFlush(any(Url.class));
    }

    @Test
    void unrelatedConstraintViolation_isNotDisguisedAsAnAliasConflict() {
        when(urlRepository.existsByShortCode("fine")).thenReturn(false);
        when(urlRepository.saveAndFlush(any(Url.class))).thenThrow(otherConflict());

        assertThatThrownBy(() -> urlService.addUrl(request("fine")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void generatedCode_collides_thenSucceedsOnANewCode() {
        when(urlRepository.saveAndFlush(any(Url.class)))
                .thenThrow(shortCodeConflict())
                .thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = urlService.addUrl(request(null));

        ArgumentCaptor<Url> saved = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository, times(2)).saveAndFlush(saved.capture());

        List<Url> attempts = saved.getAllValues();
        assertThat(attempts.get(0).getShortCode())
                .isNotEqualTo(attempts.get(1).getShortCode());   // it redrew, not retried the same code
        assertThat(attempts).allSatisfy(u -> assertThat(u.getShortCode()).hasSize(6));
        assertThat(response.shortUrl()).endsWith(attempts.get(1).getShortCode());
    }

    @Test
    void generatedCode_neverStopsCollidingAndEventuallyGivesUp() {
        when(urlRepository.saveAndFlush(any(Url.class))).thenThrow(shortCodeConflict());

        assertThatThrownBy(() -> urlService.addUrl(request(null)))
                .isInstanceOf(ShortCodeGenerationException.class);

        verify(urlRepository, times(5)).saveAndFlush(any(Url.class));   // bounded, not a spin
    }

    @Test
    void generatedCode_doesNotPreCheckTheDatabase() {
        when(urlRepository.saveAndFlush(any(Url.class))).thenAnswer(inv -> inv.getArgument(0));

        urlService.addUrl(request(null));

        // The unique index is the check; a SELECT per create bought nothing.
        verify(urlRepository, never()).existsByShortCode(any());
    }
}
