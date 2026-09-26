package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.RenewResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续约语义：持有者/令牌不变、到期只延不缩；非法、他人、过期、未持有均可区分拒绝。
 */
class RenewSemanticsTest {

    private final ManualClock clock = new ManualClock();

    private LeaseCoordinator coordinator(Path dir) {
        return new LeaseCoordinator(clock, new FileLockStateStore(dir.resolve("state.json")));
    }

    @Test
    void renewKeepsHolderAndTokenAndOnlyExtends(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant first = c.acquire("k", "node-A", 5_000L).grant();

        // 推进 1s 后续约 2s：now+ttl = 1003000 < 旧到期 1005000，剩余租期更长 => 不缩短
        clock.advance(1_000L);
        RenewResult shortRenew = c.renew("k", "node-A", 2_000L);
        assertTrue(shortRenew.renewed());
        assertEquals("node-A", shortRenew.grant().holderId());
        assertEquals(first.fencingToken(), shortRenew.grant().fencingToken(), "续约不得更换令牌");
        assertEquals(first.expireAtMillis(), shortRenew.grant().expireAtMillis(), "短 TTL 不得缩短剩余租期");

        // 续约 10s：now+ttl = 1011000 > 旧到期 => 顺延
        RenewResult longRenew = c.renew("k", "node-A", 10_000L);
        assertTrue(longRenew.renewed());
        assertEquals(first.fencingToken(), longRenew.grant().fencingToken());
        assertEquals(clock.nowMillis() + 10_000L, longRenew.grant().expireAtMillis(), "长 TTL 应向后顺延");
        System.out.printf("[TEST] renew => holder=%s token=%d 短TTL到期保持=%d 长TTL到期顺延=%d now=%d%n",
                longRenew.grant().holderId(), longRenew.grant().fencingToken(),
                shortRenew.grant().expireAtMillis(), longRenew.grant().expireAtMillis(), clock.nowMillis());
    }

    @Test
    void renewRejectsAreDistinguishable(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        c.acquire("k", "node-A", 5_000L);

        assertEquals(RejectReason.INVALID_TTL, c.renew("k", "node-A", 0L).rejectReason());
        assertEquals(RejectReason.HELD_BY_OTHER, c.renew("k", "node-B", 1_000L).rejectReason());

        clock.advance(5_000L); // 恰好到期（now == expireAt 视为已过期）
        RenewResult expired = c.renew("k", "node-A", 1_000L);
        assertFalse(expired.renewed());
        assertEquals(RejectReason.LEASE_EXPIRED, expired.rejectReason());

        RenewResult never = c.renew("missing", "node-A", 1_000L);
        assertEquals(RejectReason.LOCK_NOT_HELD, never.rejectReason());

        System.out.printf("[TEST] renew 拒绝原因 => 他人=%s 过期=%s 未持有=%s 非法=%s 均可区分%n",
                RejectReason.HELD_BY_OTHER, RejectReason.LEASE_EXPIRED,
                RejectReason.LOCK_NOT_HELD, RejectReason.INVALID_TTL);
    }
}
