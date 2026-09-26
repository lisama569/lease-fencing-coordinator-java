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
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.INVALID_PARAMETER;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.LEASE_EXPIRED;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.LOCK_HELD_BY_OTHER;
import static com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason.TOKEN_STALE;
import static com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport.assertReject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Basic acquire / renew semantics and distinguishable rejection reasons. */
class LeaseAcquireRenewTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseAcquireRenewTest.class);

    @TempDir
    Path storeDir;

    @Test
    void acquireIsExclusiveAndIdempotentForSameHolder() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        Lease first = coordinator.acquire("invoice-42", "node-A", 5_000);
        log.info("acquire#1 holder=node-A token={} acquiredAt={} expiresAt={}",
                first.fencingToken(), first.acquiredAtMs(), first.expiresAtMs());
        assertEquals(1L, first.fencingToken());

        clock.advance(1_000);
        assertReject(LOCK_HELD_BY_OTHER,
                () -> coordinator.acquire("invoice-42", "node-B", 5_000),
                "contention-while-held");

        // Same holder: idempotent, holder/token/expiry all unchanged.
        Lease again = coordinator.acquire("invoice-42", "node-A", 5_000);
        assertEquals(first, again, "repeat acquire by same holder must not change the lease");
        log.info("acquire#2 same holder returns unchanged lease token={} expiresAt={}",
                again.fencingToken(), again.expiresAtMs());
    }

    @Test
    void renewalKeepsHolderAndTokenAndNeverShortensLease() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        Lease granted = coordinator.acquire("job-7", "worker-1", 10_000);
        clock.advance(2_000);

        // A shorter TTL must not shorten the remaining lease.
        Lease shortRenew = coordinator.renew("job-7", "worker-1", granted.fencingToken(), 3_000);
        assertEquals(granted.expiresAtMs(), shortRenew.expiresAtMs(),
                "renewal with shorter TTL must keep the later existing expiry");
        assertEquals(granted.holderId(), shortRenew.holderId());
        assertEquals(granted.fencingToken(), shortRenew.fencingToken());
        log.info("renew with ttl=3000 at now={} leaves expiresAt={} (no shortening)",
                clock.nowMillis(), shortRenew.expiresAtMs());

        // A longer TTL extends from now.
        Lease longRenew = coordinator.renew("job-7", "worker-1", granted.fencingToken(), 20_000);
        assertEquals(clock.nowMillis() + 20_000, longRenew.expiresAtMs());
        log.info("renew with ttl=20000 at now={} extends expiresAt={}",
                clock.nowMillis(), longRenew.expiresAtMs());

        assertReject(HOLDER_MISMATCH,
                () -> coordinator.renew("job-7", "worker-2", granted.fencingToken(), 10_000),
                "renew-by-other-holder");
        assertReject(TOKEN_STALE,
                () -> coordinator.renew("job-7", "worker-1", 999L, 10_000),
                "renew-with-wrong-token");
    }

    @Test
    void expiredLeaseCannotBeRenewedAndInvalidParametersAreRejected() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        Lease granted = coordinator.acquire("job-8", "worker-1", 1_000);
        clock.advance(1_000); // now == expiresAt -> expired
        assertReject(LEASE_EXPIRED,
                () -> coordinator.renew("job-8", "worker-1", granted.fencingToken(), 10_000),
                "renew-after-expiry");

        assertReject(INVALID_PARAMETER,
                () -> coordinator.acquire("  ", "worker-1", 1_000), "blank-lock-name");
        assertReject(INVALID_PARAMETER,
                () -> coordinator.acquire("job-9", "worker-1", 0), "zero-ttl");
        assertReject(INVALID_PARAMETER,
                () -> coordinator.acquire("job-9", "worker-1", -5), "negative-ttl");
    }

    @Test
    void fenceValidationAcceptsOnlyCurrentTokenOfCurrentHolder() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        Lease a = coordinator.acquire("resource-1", "node-A", 1_000);
        FenceDecision ok = coordinator.validateFencedToken("resource-1", "node-A", a.fencingToken());
        assertTrue(ok.accepted());
        log.info("fence check token={} accepted detail='{}'", a.fencingToken(), ok.detail());

        FenceDecision wrongHolder =
                coordinator.validateFencedToken("resource-1", "node-B", a.fencingToken());
        assertFalse(wrongHolder.accepted());
        assertEquals(HOLDER_MISMATCH, wrongHolder.reason());

        FenceDecision futureToken =
                coordinator.validateFencedToken("resource-1", "node-A", a.fencingToken() + 50);
        assertFalse(futureToken.accepted());
        assertEquals(TOKEN_STALE, futureToken.reason());
        log.info("fence check token={} rejected reason={} detail='{}'",
                a.fencingToken() + 50, futureToken.reason(), futureToken.detail());
    }
}
