package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.lease.FenceDecision;
import com.github.lisama569.leasefencingcoordinatorjava.lease.Lease;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.support.FakeClock;
import com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.LEASE_EXPIRED;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.LOCK_HELD_BY_OTHER;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.TOKEN_SUPERSEDED;
import static com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport.assertReject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expiry-based takeover and fencing token monotonicity / supersession. */
class LeaseTakeoverFenceTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseTakeoverFenceTest.class);

    @TempDir
    Path storeDir;

    @Test
    void afterExpiryAnotherCallerTakesOverWithStrictlyHigherToken() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        Lease first = coordinator.acquire("ledger", "owner-1", 10_000);
        log.info("generation-1 holder=owner-1 token={} now={} expiresAt={}",
                first.fencingToken(), clock.nowMillis(), first.expiresAtMs());

        // Before expiry the challenger cannot take over.
        clock.advance(9_999);
        assertReject(LOCK_HELD_BY_OTHER,
                () -> coordinator.acquire("ledger", "owner-2", 10_000),
                "takeover-just-before-expiry");

        // At exactly expiresAt the lease is expired: takeover succeeds.
        clock.advance(1);
        Lease second = coordinator.acquire("ledger", "owner-2", 10_000);
        log.info("generation-2 holder=owner-2 token={} now={} expiresAt={}",
                second.fencingToken(), clock.nowMillis(), second.expiresAtMs());
        assertEquals("owner-2", second.holderId());
        assertEquals(first.fencingToken() + 1, second.fencingToken(),
                "takeover token must be strictly greater");

        // Old holder's stale token is rejected by the fence.
        FenceDecision stale = coordinator.validateFencedToken(
                "ledger", "owner-1", first.fencingToken());
        assertFalse(stale.accepted());
        assertEquals(TOKEN_SUPERSEDED, stale.reason());
        log.info("fence oldHolder=owner-1 oldToken={} rejected reason={} currentToken={} detail='{}'",
                first.fencingToken(), stale.reason(),
                stale.current().fencingToken(), stale.detail());

        // Even the new holder presenting the old token is rejected.
        FenceDecision oldTokenByNewHolder = coordinator.validateFencedToken(
                "ledger", "owner-2", first.fencingToken());
        assertFalse(oldTokenByNewHolder.accepted());
        assertEquals(TOKEN_SUPERSEDED, oldTokenByNewHolder.reason());

        FenceDecision current = coordinator.validateFencedToken(
                "ledger", "owner-2", second.fencingToken());
        assertTrue(current.accepted());
        log.info("fence holder=owner-2 token={} accepted detail='{}'",
                second.fencingToken(), current.detail());

        // Multiple generations keep increasing; expiry rejects protected ops too.
        clock.advance(10_000);
        Lease third = coordinator.acquire("ledger", "owner-3", 10_000);
        assertEquals(second.fencingToken() + 1, third.fencingToken());

        clock.advance(10_000);
        FenceDecision expired = coordinator.validateFencedToken(
                "ledger", "owner-3", third.fencingToken());
        assertEquals(LEASE_EXPIRED, expired.reason(),
                "at expiry instant the current token must no longer be accepted");
        log.info("generation-3 token={} expiresAt={}; fence at now={} rejected reason={}",
                third.fencingToken(), third.expiresAtMs(), clock.nowMillis(), expired.reason());

        clock.advance(1);
        FenceDecision earlierAfterThirdExpiry = coordinator.validateFencedToken(
                "ledger", "owner-2", second.fencingToken());
        assertFalse(earlierAfterThirdExpiry.accepted());
        log.info("fence earlier token={} after gen-3 expiry rejected reason={}",
                second.fencingToken(), earlierAfterThirdExpiry.reason());
    }
}
