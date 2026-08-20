package com.teya.tinyledger.service;

import com.teya.tinyledger.LedgerProperties;
import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import com.teya.tinyledger.domain.Transaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Remembers what an Idempotency-Key produced, so a client that retries after a timeout replays the
 * original result instead of moving money a second time.
 *
 * <p>Keys are supplied by clients and are therefore unbounded, so entries are capped and expire.
 */
@Component
public class IdempotencyStore {

    private record Key(UUID accountId, String value) {
    }

    /** A null result means the attempt never reported one, whether or not it is still running. */
    private record Attempt(String fingerprint, Transaction result, Instant startedAt) {
    }

    private final Map<Key, Attempt> attempts;
    private final Duration ttl;
    private final Clock clock;

    public IdempotencyStore(LedgerProperties properties, Clock clock) {
        this.ttl = properties.idempotencyTtl();
        this.clock = clock;

        int maxEntries = properties.maxIdempotencyKeys();
        this.attempts = Collections.synchronizedMap(new LinkedHashMap<Key, Attempt>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Attempt> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /**
     * Claims the key for this request, before the movement runs, so that a second request carrying
     * the same key can tell one is already underway. Returns the earlier transaction if this key
     * already produced one, or empty if the caller should go ahead and perform the movement.
     */
    public Optional<Transaction> claim(UUID accountId, String key, String fingerprint) {
        Key mapKey = new Key(accountId, key);
        Attempt fresh = new Attempt(fingerprint, null, clock.instant());

        Attempt existing = attempts.putIfAbsent(mapKey, fresh);
        if (existing == null || hasExpired(existing)) {
            attempts.put(mapKey, fresh);
            return Optional.empty();
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used for a different request");
        }
        if (existing.result() == null) {
            // Either still running or abandoned by an attempt that failed without saying whether
            // it moved any money. Both are refused: we cannot prove the money did not move, and
            // refusing a legitimate retry is recoverable where a second movement is not.
            throw new LedgerException(ErrorCode.IDEMPOTENT_REQUEST_UNRESOLVED,
                    "another request with this key has not reported an outcome; "
                            + "check the account before sending it again");
        }
        return Optional.of(existing.result());
    }

    /** Records what the movement produced, so a later retry replays it instead of repeating it. */
    public void complete(UUID accountId, String key, Transaction result) {
        attempts.computeIfPresent(new Key(accountId, key),
                (k, attempt) -> new Attempt(attempt.fingerprint(), result, attempt.startedAt()));
    }

    /** Gives the claim back when nothing was recorded, so the client can correct it and retry. */
    public void unclaim(UUID accountId, String key) {
        attempts.remove(new Key(accountId, key));
    }

    private boolean hasExpired(Attempt attempt) {
        return Duration.between(attempt.startedAt(), clock.instant()).compareTo(ttl) > 0;
    }
}
