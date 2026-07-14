package uk.gov.moj.cp.ai.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

    private static final String KEY = "7f3ad231-1c1d-4d3e-8c2f-0a1b2c3d4e5f";
    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    private IdempotencyStatusStore store;
    private IdempotencyGuard guard;
    private IdempotentWork work;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStatusStore.class);
        work = mock(IdempotentWork.class);
        guard = new IdempotencyGuard(store, TTL, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OffsetDateTime now() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Skips without claiming when the row is already terminal")
    void skipsWhenTerminal() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(new LeaseSnapshot("DONE", READ_ETAG, null, null));
        when(store.isTerminal("DONE")).thenReturn(true);

        assertEquals(GuardOutcome.SKIPPED_TERMINAL, guard.runOnce(KEY, work));

        verify(work, never()).run(any());
        verify(store, never()).claimLease(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Claims a free row (lease TTL from the clock) and runs the work with the claim token")
    void claimsFreeRowAndRunsWork() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(new LeaseSnapshot("PENDING", READ_ETAG, null, null));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), eq(now().plus(TTL)))).thenReturn(CLAIM_ETAG);

        assertEquals(GuardOutcome.EXECUTED, guard.runOnce(KEY, work));

        verify(work).run(new ClaimToken(KEY, CLAIM_ETAG));
        verify(store, never()).releaseLease(anyString(), anyString());
    }

    @Test
    @DisplayName("Throws LeaseConflictException without claiming when a live lease exists")
    void throwsLeaseConflictWhenLiveLease() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(
                new LeaseSnapshot("PENDING", READ_ETAG, now().plusMinutes(5), "other-owner"));
        when(store.isTerminal("PENDING")).thenReturn(false);

        assertThrows(LeaseConflictException.class, () -> guard.runOnce(KEY, work));

        verify(work, never()).run(any());
        verify(store, never()).claimLease(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Reclaims an expired lease via the conditional write")
    void reclaimsExpiredLease() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(
                new LeaseSnapshot("PENDING", READ_ETAG, now().minusMinutes(5), "crashed-owner"));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any())).thenReturn(CLAIM_ETAG);

        assertEquals(GuardOutcome.EXECUTED, guard.runOnce(KEY, work));

        verify(work).run(new ClaimToken(KEY, CLAIM_ETAG));
    }

    @Test
    @DisplayName("A lease expiring exactly now is reclaimable (not live)")
    void leaseExpiringExactlyNowIsReclaimable() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(
                new LeaseSnapshot("PENDING", READ_ETAG, now(), "other-owner"));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any())).thenReturn(CLAIM_ETAG);

        assertEquals(GuardOutcome.EXECUTED, guard.runOnce(KEY, work));
    }

    @Test
    @DisplayName("Losing the claim race (etag mismatch) surfaces as LeaseConflictException")
    void claimRaceLossSurfacesAsLeaseConflict() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(new LeaseSnapshot("PENDING", READ_ETAG, null, null));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any()))
                .thenThrow(new EtagMismatchException("etag changed"));

        assertThrows(LeaseConflictException.class, () -> guard.runOnce(KEY, work));

        verify(work, never()).run(any());
    }

    @Test
    @DisplayName("Releases the lease then rethrows when the work fails")
    void releasesLeaseAndRethrowsOnWorkFailure() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(new LeaseSnapshot("PENDING", READ_ETAG, null, null));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any())).thenReturn(CLAIM_ETAG);

        final RuntimeException failure = new RuntimeException("work failed");
        org.mockito.Mockito.doThrow(failure).when(work).run(any());

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> guard.runOnce(KEY, work));

        assertSame(failure, thrown);
        verify(store).releaseLease(KEY, CLAIM_ETAG);
    }

    @Test
    @DisplayName("Does NOT release the lease when the work fails with a fence loss (etag already stale)")
    void doesNotReleaseLeaseOnFenceLoss() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(new LeaseSnapshot("PENDING", READ_ETAG, null, null));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any())).thenReturn(CLAIM_ETAG);

        org.mockito.Mockito.doThrow(new EtagMismatchException("etag changed")).when(work).run(any());

        assertThrows(EtagMismatchException.class, () -> guard.runOnce(KEY, work));

        verify(store, never()).releaseLease(anyString(), anyString());
    }

    @Test
    @DisplayName("Creates a claimed row defensively when the status row is missing")
    void createsClaimedRowWhenRowMissing() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(null);
        when(store.createClaimedRow(eq(KEY), anyString(), eq(now().plus(TTL)))).thenReturn(CLAIM_ETAG);

        assertEquals(GuardOutcome.EXECUTED, guard.runOnce(KEY, work));

        verify(work).run(new ClaimToken(KEY, CLAIM_ETAG));
        verify(store, never()).claimLease(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Falls back to a re-read when the defensive insert races a concurrent creator")
    void reReadsWhenDefensiveInsertRacesConcurrentCreator() throws Exception {
        when(store.readForClaim(KEY))
                .thenReturn(null)
                .thenReturn(new LeaseSnapshot("PENDING", READ_ETAG, null, null));
        when(store.createClaimedRow(eq(KEY), anyString(), any()))
                .thenThrow(new DuplicateRecordException("already exists"));
        when(store.isTerminal("PENDING")).thenReturn(false);
        when(store.claimLease(eq(KEY), eq(READ_ETAG), anyString(), any())).thenReturn(CLAIM_ETAG);

        assertEquals(GuardOutcome.EXECUTED, guard.runOnce(KEY, work));

        verify(work).run(new ClaimToken(KEY, CLAIM_ETAG));
    }

    @Test
    @DisplayName("Skips when the re-read after a racing insert finds a terminal row")
    void skipsWhenReReadAfterRacingInsertFindsTerminalRow() throws Exception {
        when(store.readForClaim(KEY))
                .thenReturn(null)
                .thenReturn(new LeaseSnapshot("DONE", READ_ETAG, null, null));
        when(store.createClaimedRow(eq(KEY), anyString(), any()))
                .thenThrow(new DuplicateRecordException("already exists"));
        when(store.isTerminal("DONE")).thenReturn(true);

        assertEquals(GuardOutcome.SKIPPED_TERMINAL, guard.runOnce(KEY, work));

        verify(work, never()).run(any());
    }

    @Test
    @DisplayName("Throws LeaseConflictException when the row vanishes between the racing insert and the re-read")
    void throwsLeaseConflictWhenRowVanishesAfterRacingInsert() throws Exception {
        when(store.readForClaim(KEY)).thenReturn(null).thenReturn(null);
        when(store.createClaimedRow(eq(KEY), anyString(), any()))
                .thenThrow(new DuplicateRecordException("already exists"));

        assertThrows(LeaseConflictException.class, () -> guard.runOnce(KEY, work));
    }
}
