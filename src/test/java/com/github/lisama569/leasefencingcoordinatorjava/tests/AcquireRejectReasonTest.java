package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基本获取语义与“可区分拒绝原因”覆盖。
 */
class AcquireRejectReasonTest {

    private final ManualClock clock = new ManualClock();

    private LeaseCoordinator coordinator(Path dir) {
        return new LeaseCoordinator(clock, new FileLockStateStore(dir.resolve("state.json")));
    }

    @Test
    void firstAcquireGrantsTokenOne(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        AcquireResult r = c.acquire("order-42", "node-A", 5_000L);

        assertTrue(r.granted());
        assertNotNull(r.grant());
        assertEquals("node-A", r.grant().holderId());
        assertEquals(1L, r.grant().fencingToken());
        assertEquals(clock.nowMillis() + 5_000L, r.grant().expireAtMillis());
        assertFalse(r.grant().takeover());
        System.out.printf("[TEST] acquire 空闲锁 => holder=%s token=%d expireAt=%d 判定=%s%n",
                r.grant().holderId(), r.grant().fencingToken(), r.grant().expireAtMillis(), r.message());
    }

    @Test
    void concurrentHolderRejectedWhileLeaseValid(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        c.acquire("order-42", "node-A", 5_000L);
        AcquireResult other = c.acquire("order-42", "node-B", 5_000L);

        assertFalse(other.granted());
        assertEquals(RejectReason.HELD_BY_OTHER, other.rejectReason());
        assertNull(other.grant());
        System.out.printf("[TEST] acquire 竞争 => granted=%s reason=%s now=%d msg=%s%n",
                other.granted(), other.rejectReason(), other.evaluatedAtMillis(), other.message());
    }

    @Test
    void reentrantAcquireBySameHolderReturnsSameGrantUnchanged(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        AcquireResult first = c.acquire("order-42", "node-A", 5_000L);
        clock.advance(1_000L);
        AcquireResult again = c.acquire("order-42", "node-A", 9_999L);

        assertTrue(again.granted());
        assertEquals(first.grant().fencingToken(), again.grant().fencingToken(), "重入不得换令牌");
        assertEquals(first.grant().expireAtMillis(), again.grant().expireAtMillis(), "重入不得改变到期时间");
        System.out.printf("[TEST] acquire 同持有者重入 => token=%d expireAt=%d(保持不变) 判定=%s%n",
                again.grant().fencingToken(), again.grant().expireAtMillis(), again.message());
    }

    @Test
    void invalidArgumentsHaveDistinctReasons(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);

        assertEquals(RejectReason.INVALID_LOCK_NAME, c.acquire("  ", "node-A", 10L).rejectReason());
        assertEquals(RejectReason.INVALID_HOLDER_ID, c.acquire("l", "", 10L).rejectReason());
        assertEquals(RejectReason.INVALID_TTL, c.acquire("l", "node-A", 0L).rejectReason());
        assertEquals(RejectReason.INVALID_TTL, c.acquire("l", "node-A", -1L).rejectReason());
        assertEquals(RejectReason.INVALID_TTL,
                c.acquire("l", "node-A", Long.MAX_VALUE).rejectReason());

        System.out.println("[TEST] 参数非法 => INVALID_LOCK_NAME / INVALID_HOLDER_ID / INVALID_TTL 三种原因均可区分");
    }
}
