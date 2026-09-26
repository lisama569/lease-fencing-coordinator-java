package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到期接管与栅栏令牌保护：令牌严格递增；过期/被取代/更早/未知令牌的操作一律拒绝。
 */
class TakeoverFencingTest {

    private final ManualClock clock = new ManualClock();

    private LeaseCoordinator coordinator(Path dir) {
        return new LeaseCoordinator(clock, new FileLockStateStore(dir.resolve("state.json")));
    }

    @Test
    void takeoverAfterExpiryIssuesStrictlyGreaterToken(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        clock.advance(4_999L);
        assertEquals(RejectReason.HELD_BY_OTHER, c.acquire("k", "node-B", 5_000L).rejectReason(),
                "未到期不得接管");

        clock.advance(1L); // now == expireAt
        AcquireResult takeover = c.acquire("k", "node-B", 5_000L);
        assertTrue(takeover.granted());
        assertTrue(takeover.grant().takeover());
        assertEquals("node-B", takeover.grant().holderId());
        assertEquals(a.fencingToken() + 1L, takeover.grant().fencingToken(), "接管必须发放严格更大的令牌");

        // 旧持有者再出现，其旧令牌必须已失效
        FenceCheckResult staleA = c.validateProtectedOperation("k", a.fencingToken());
        assertFalse(staleA.accepted());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED, staleA.rejectReason());

        System.out.printf("[TEST] takeover => prevHolder=node-A prevToken=%d newHolder=%s newToken=%d now=%d; 旧令牌操作=%s%n",
                a.fencingToken(), takeover.grant().holderId(), takeover.grant().fencingToken(),
                clock.nowMillis(), staleA.rejectReason());
    }

    @Test
    void fenceCheckAcceptsOnlyCurrentTokenWhileValid(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        assertTrue(c.validateProtectedOperation("k", a.fencingToken()).accepted(), "当前令牌 + 未到期 => 接受");

        assertEquals(RejectReason.INVALID_FENCING_TOKEN,
                c.validateProtectedOperation("k", 0L).rejectReason());
        assertEquals(RejectReason.INVALID_FENCING_TOKEN,
                c.validateProtectedOperation("k", -3L).rejectReason());
        assertEquals(RejectReason.UNKNOWN_FENCING_TOKEN,
                c.validateProtectedOperation("k", a.fencingToken() + 5L).rejectReason(), "更大但从未发放 => 未知令牌");
        assertEquals(RejectReason.LOCK_NOT_HELD,
                c.validateProtectedOperation("nope", 1L).rejectReason());

        // A 过期但尚未有人接管：当前令牌不变，但租约过期 => 过期拒绝，绝不静默接受
        clock.advance(5_000L);
        FenceCheckResult expired = c.validateProtectedOperation("k", a.fencingToken());
        assertFalse(expired.accepted());
        assertEquals(RejectReason.LEASE_EXPIRED, expired.rejectReason());

        // B 接管后 A 的令牌成为更早一代
        LeaseGrant b = c.acquire("k", "node-B", 5_000L).grant();
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                c.validateProtectedOperation("k", a.fencingToken()).rejectReason(), "更早令牌必须被拒绝");
        assertTrue(c.validateProtectedOperation("k", b.fencingToken()).accepted(), "新持有者当前令牌 => 接受");

        System.out.printf("[TEST] fence => tokenA=%d 过期判定=%s; tokenB=%d 接管后旧令牌=%s 新令牌=ACCEPT now=%d%n",
                a.fencingToken(), RejectReason.LEASE_EXPIRED, b.fencingToken(),
                RejectReason.FENCING_TOKEN_SUPERSEDED, clock.nowMillis());
    }
}
