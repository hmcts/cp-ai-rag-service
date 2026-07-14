package uk.gov.moj.cp.ai.idempotency;

import static org.slf4j.LoggerFactory.getLogger;

import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;

/**
 * Effectively-once guard for queue-triggered work (design: docs/idempotency-rag-service.md).
 * <p>
 * {@code runOnce(key, work)} reads the status row for the key; skips if the status is already
 * terminal; otherwise claims an in-progress lease via an ETag-conditioned write (the first
 * worker wins, a concurrent duplicate loses with {@link LeaseConflictException}) and runs the
 * work with a {@link ClaimToken}. The token's ETag fences the work's terminal write: a worker
 * whose lease was reclaimed after expiry cannot overwrite the reclaimer's outcome.
 * <p>
 * The lease owner id is diagnostic only — the ETag is the actual mutex.
 */
public class IdempotencyGuard {

    private static final Logger LOGGER = getLogger(IdempotencyGuard.class);

    private final IdempotencyStatusStore store;
    private final Duration leaseTtl;
    private final Clock clock;

    public IdempotencyGuard(final IdempotencyStatusStore store, final Duration leaseTtl) {
        this(store, leaseTtl, Clock.systemUTC());
    }

    IdempotencyGuard(final IdempotencyStatusStore store, final Duration leaseTtl, final Clock clock) {
        this.store = store;
        this.leaseTtl = leaseTtl;
        this.clock = clock;
    }

    /**
     * @throws LeaseConflictException if another worker holds a live lease on the key (or won
     *                                the claim race) — rethrow a retryable exception so the
     *                                message redelivers and re-checks
     * @throws Exception              whatever the work throws; the lease is released first so
     *                                the next delivery can re-claim immediately
     */
    public GuardOutcome runOnce(final String key, final IdempotentWork work) throws Exception {
        final ClaimToken token = claim(key);
        if (token == null) {
            return GuardOutcome.SKIPPED_TERMINAL;
        }

        try {
            work.run(token);
        } catch (Exception e) {
            // On a fence loss the claim etag is already stale — a release would be a
            // guaranteed 412 and only produce a misleading "release failed" warning.
            if (!(e instanceof EtagMismatchException)) {
                store.releaseLease(key, token.etag());
            }
            throw e;
        }
        return GuardOutcome.EXECUTED;
    }

    /** Returns the winning claim, or {@code null} when the row is terminal (skip). */
    private ClaimToken claim(final String key) throws EntityRetrievalException {
        final String owner = UUID.randomUUID().toString();

        LeaseSnapshot snapshot = store.readForClaim(key);

        if (snapshot == null) {
            try {
                final String etag = store.createClaimedRow(key, owner, expiry());
                LOGGER.warn("Status row was missing for key={} — created claimed row defensively", key);
                return new ClaimToken(key, etag);
            } catch (DuplicateRecordException e) {
                snapshot = store.readForClaim(key);
                if (snapshot == null) {
                    throw new LeaseConflictException("Status row for key '" + key + "' appeared then vanished during claim", e);
                }
            }
        }

        if (store.isTerminal(snapshot.status())) {
            LOGGER.info("Skipping already-terminal work for key={} (status={})", key, snapshot.status());
            return null;
        }

        final OffsetDateTime now = OffsetDateTime.now(clock);
        if (snapshot.leaseExpiresAt() != null && snapshot.leaseExpiresAt().isAfter(now)) {
            throw new LeaseConflictException("Live lease on key '" + key + "' held by owner '" + snapshot.leaseOwner()
                    + "' until " + snapshot.leaseExpiresAt());
        }

        try {
            final String etag = store.claimLease(key, snapshot.etag(), owner, expiry());
            LOGGER.info("Claimed idempotency lease for key={} (owner={})", key, owner);
            return new ClaimToken(key, etag);
        } catch (EtagMismatchException e) {
            throw new LeaseConflictException("Lost the claim race for key '" + key + "'", e);
        }
    }

    private OffsetDateTime expiry() {
        return OffsetDateTime.now(clock).plus(leaseTtl);
    }
}
