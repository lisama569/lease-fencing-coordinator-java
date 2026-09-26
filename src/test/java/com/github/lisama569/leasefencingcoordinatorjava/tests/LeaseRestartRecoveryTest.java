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

import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.HOLDER_MISMATCH;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.LOCK_HELD_BY_OTHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport.assertReject;

/**
 * Simulated process restart: a brand-new coordinator instance over the
 * same durable store must reproduce holder, token and expiry verdicts.
 */
class LeaseRestartRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseRestartRecoveryTest.class);

    @TempDir
    Path storeDir;

    @Test
    void restartPreservesUnexpiredLeaseTokenAndExpiry() {
        FakeClock clock = new FakeClock();

        LeaseCoordinator before = LeaseTestSupport.newCoordinator(storeDir, clock);
        Lease granted = before.acquire("durable-lock", "node-A", 10_000);
        log.info("before-restart holder=node-A token={} acquiredAt={} expiresAt={} now={}",
                granted.fencingToken(), granted.acquiredAtMs(),
                granted.expiresAtMs(), clock.nowMillis());

        clock.advance(3_000);

        // Simulate process restart: fresh coordinator, same on-disk state.
        LeaseCoordinator after = LeaseTestSupport.newCoordinator(storeDir, clock);

        // Absolute expiry survives: a challenger still cannot acquire.
        assertReject(LOCK_HELD_BY_OTHER,
                () -> after.acquire("durable-lock", "node-B", 10_000),
                "challenger-after-restart-within-ttl");

        // The holder's token still validates; token is never re-granted.
        FenceDecision ok = after.validateFencedToken(
                "durable-lock", "node-A", granted.fencingToken());
        assertTrue(ok.accepted(), "unexpired lease must remain valid after restart");
        assertEquals(granted, ok.current(),
                "restored record must be identical (holder/token/timestamps)");
        log.info("after-restart fence token={} accepted expiresAt={} now={} basis=durable_state",
                granted.fencingToken(), ok.current().expiresAtMs(), clock.nowMillis());

        assertReject(HOLDER_MISMATCH,
                () -> after.renew("durable-lock", "node-B", granted.fencingToken(), 10_000),
                "renew-wrong-holder-after-restart");

        // Restart does not reopen a double-hold window: the original holder's
        // idempotent re-acquire returns exactly the same token.
        Lease reacquire = after.acquire("durable-lock", "node-A", 10_000);
        assertEquals(granted, reacquire,
                "restart must not mint a new token for the still-valid holder");
    }

    @Test
    void restartAfterExpiryAllowsTakeoverButNeverReissuesToken() {
        FakeClock clock = new FakeClock();

        LeaseCoordinator before = LeaseTestSupport.newCoordinator(storeDir, clock);
        Lease first = before.acquire("durable-lock", "node-A", 10_000);
        log.info("generation-1 holder=node-A token={} expiresAt={}",
                first.fencingToken(), first.expiresAtMs());

        clock.advance(10_001);

        // Restart after the absolute expiry instant.
        LeaseCoordinator after = LeaseTestSupport.newCoordinator(storeDir, clock);

        FenceDecision expired = after.validateFencedToken(
                "durable-lock", "node-A", first.fencingToken());
        assertFalse(expired.accepted());
        log.info("after-restart expired fence token={} now={} rejected reason={}",
                first.fencingToken(), clock.nowMillis(), expired.reason());

        Lease second = after.acquire("durable-lock", "node-B", 10_000);
        assertEquals(first.fencingToken() + 1, second.fencingToken(),
                "token 1 must never be re-issued after restart");
        assertEquals("node-B", second.holderId());
        log.info("generation-2 after restart holder=node-B token={} acquiredAt={} expiresAt={}",
                second.fencingToken(), second.acquiredAtMs(), second.expiresAtMs());

        // The old holder and old token are both dead after restart.
        FenceDecision stale = after.validateFencedToken(
                "durable-lock", "node-A", first.fencingToken());
        assertFalse(stale.accepted());
        FenceDecision oldHolderCurrentToken = after.validateFencedToken(
                "durable-lock", "node-A", second.fencingToken());
        assertFalse(oldHolderCurrentToken.accepted());
        log.info("stale old-holder verdicts after restart: reason1={} reason2={}",
                stale.reason(), oldHolderCurrentToken.reason());
    }
}
