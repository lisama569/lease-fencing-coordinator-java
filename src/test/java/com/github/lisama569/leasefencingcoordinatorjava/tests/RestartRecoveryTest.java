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
 * 进程重启恢复：用同一个状态文件构造全新协调器实例来模拟重启。
 * 已发放令牌不得重发；未到期/已过期租约判定与重启前一致；重启不产生双持有窗口。
 */
class RestartRecoveryTest {

    @Test
    void stateSurvivesRestartAndTokensAreNeverReissued(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        ManualClock clock = new ManualClock(2_000_000L);

        // 第一次“进程”：A 获取锁，随后到期，B 接管 -> 已发放令牌 1、2
        LeaseCoordinator first = new LeaseCoordinator(clock, new FileLockStateStore(stateFile));
        LeaseGrant a = first.acquire("k", "node-A", 5_000L).grant();
        clock.advance(6_000L);
        LeaseGrant b = first.acquire("k", "node-B", 5_000L).grant();
        assertEquals(2L, b.fencingToken());

        // 重启：新协调器 + 新时钟对象，但时间点与宕机时刻相同
        ManualClock restartedClock = new ManualClock(clock.nowMillis());
        LeaseCoordinator second = new LeaseCoordinator(restartedClock, new FileLockStateStore(stateFile));

        // B 的租约仍未到期：重启后判定必须一致
        assertTrue(second.inspect("k").held(), "重启后未到期租约仍为有效持有");
        assertEquals("node-B", second.inspect("k").holderId());
        assertEquals(2L, second.inspect("k").fencingToken());
        assertTrue(second.validateProtectedOperation("k", 2L).accepted());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                second.validateProtectedOperation("k", 1L).rejectReason(), "更早令牌重启后依旧被拒绝");

        // 重启绝不产生双持有窗口：此刻 C 必须仍被 B 挡在门外
        AcquireResult c = second.acquire("k", "node-C", 1_000L);
        assertFalse(c.granted());
        assertEquals(RejectReason.HELD_BY_OTHER, c.rejectReason());

        // 已发放令牌不得重发：等 B 到期后由 C 接管，必须拿到 3 而不是重新从 1 开始
        restartedClock.advance(5_000L);
        LeaseGrant cGrant = second.acquire("k", "node-C", 5_000L).grant();
        assertEquals(3L, cGrant.fencingToken(), "重启后接管不得重发历史令牌");

        System.out.printf("[TEST] restart 恢复 => 持有者=%s 当前令牌=%d(非重发) A的旧令牌=%s C接管新令牌=%d 重启时并发获取=%s%n",
                "node-B", 2L, RejectReason.FENCING_TOKEN_SUPERSEDED, cGrant.fencingToken(),
                RejectReason.HELD_BY_OTHER);
    }

    @Test
    void expiredLeaseStaysExpiredAfterRestart(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        ManualClock clock = new ManualClock(3_000_000L);
        LeaseCoordinator first = new LeaseCoordinator(clock, new FileLockStateStore(stateFile));
        LeaseGrant a = first.acquire("k", "node-A", 1_000L).grant();
        clock.advance(2_000L); // A 已到期
        assertEquals(RejectReason.LEASE_EXPIRED,
                first.validateProtectedOperation("k", a.fencingToken()).rejectReason());

        // 重启后时间更靠后：过期结论必须保持一致
        ManualClock laterClock = new ManualClock(clock.nowMillis() + 10_000L);
        LeaseCoordinator second = new LeaseCoordinator(laterClock, new FileLockStateStore(stateFile));
        FenceCheckResult stillExpired = second.validateProtectedOperation("k", a.fencingToken());
        assertFalse(stillExpired.accepted());
        assertEquals(RejectReason.LEASE_EXPIRED, stillExpired.rejectReason(),
                "令牌仍是当前一代但租约已到期 => LEASE_EXPIRED；接管后旧令牌才变为 SUPERSEDED");
        assertFalse(second.inspect("k").held(), "重启后过期租约不得仍判为有效持有");
        assertTrue(second.inspect("k").expired());

        // 过期锁可被立即接管，发放下一个令牌
        assertEquals(2L, second.acquire("k", "node-B", 1_000L).grant().fencingToken());

        System.out.printf("[TEST] restart 过期一致性 => holder=%s token=%d held=%s expired=%s 接管后令牌=2 now=%d%n",
                "node-A", a.fencingToken(), false, true, laterClock.nowMillis());
    }
}
