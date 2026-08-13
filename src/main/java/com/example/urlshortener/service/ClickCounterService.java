package com.example.urlshortener.service;

import com.example.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClickCounterService {

    private static final String PENDING  = "clicks:pending";    // counts piling up
    private static final String FLUSHING = "clicks:flushing";   // snapshot being written

    private final RedisTemplate<String, String> redisTemplate;
    private final UrlRepository urlRepository;

    /** Called on every redirect. One Redis command, no Postgres. */
    public void increment(String shortCode) {
        redisTemplate.opsForHash().increment(PENDING, shortCode, 1L);
    }

    /** How many clicks Postgres hasn't heard about yet. Used by analytics (step 5). */
    public long pendingFor(String shortCode) {
        Object v = redisTemplate.opsForHash().get(PENDING, shortCode);
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    /** Runs 30s after the previous run finished. */
    @Scheduled(fixedDelay = 30_000)
    public void flush() {
        // A previous run died before it could DEL. Finish its work first,
        // otherwise the RENAME below would overwrite those counts.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(FLUSHING))) {
            drain();
        }
        // No clicks since last time. RENAME would throw, so just leave.
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(PENDING))) {
            return;
        }
        redisTemplate.rename(PENDING, FLUSHING);   // atomic snapshot
        drain();
    }

    /** Write whatever is in FLUSHING to Postgres, then clear it. */
    private void drain() {
        Map<Object, Object> counts = redisTemplate.opsForHash().entries(FLUSHING);
        if (counts.isEmpty()) {
            redisTemplate.delete(FLUSHING);
            return;
        }
        try {
            counts.forEach((code, delta) ->
                    urlRepository.addToClickCount(code.toString(), Long.parseLong(delta.toString())));
            redisTemplate.delete(FLUSHING);   // only once the writes actually succeeded
            log.debug("Flushed {} click counters", counts.size());
        } catch (Exception e) {
            // Deliberately don't delete: the next cycle will retry this snapshot.
            // Worst case we count some clicks twice, which beats losing them.
            log.error("Click flush failed, {} counters retained for retry", counts.size(), e);
        }
    }

    /**
     * On a normal shutdown, don't throw away the last 30s of counts.
     */
    @EventListener(ContextClosedEvent.class)
    public void flushOnShutdown() {
        try {
            flush();
        } catch (Exception e) {
            log.error("Shutdown flush failed", e);
        }
    }
}

// claude --resume 9e884ebf-95fc-4bb1-aa03-0197fc4eaacf