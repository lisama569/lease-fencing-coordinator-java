package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.ReleaseResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主动让出语义：持有者到期前可主动放弃；让出对所有共用实例立即生效；
 * 让出后其他调用方可立即获取且令牌更大；非持有者 / 已过期 / 已让出 / 未持有的拒绝互相可区分。
 */
class ReleaseSemanticsTest {

    private final ManualClock clock = new ManualClock(9_000_000L);

    private LeaseCoordinator newInstance(Path stateFile, String tag) {
        System.out.printf("[TEST] 启动实例 tag=%s%n", tag);
        return new LeaseCoordinator(clock, new FileLockStateStore(stateFile));
    }

    @Test
    void releaseTakesEffectImmediatelyAcrossInstancesAndNextTokenGreater(@TempDir Path dir) {
        Path stateFile = dir.resolve("shared-state.json");
        LeaseCoordinator i1 = newInstance(stateFile, "instance-1");
        LeaseCoordinator i2 = newInstance(stateFile, "instance-2");

        LeaseGrant a = i1.acquire("job/9", "node-A", 10_000L).grant();
        assertEquals(1L, a.fencingToken());

        // A 提前完成，在实例 1 上主动让出（此时远未到期）
        ReleaseResult released = i1.release("job/9", "node-A");
        assertTrue(released.released());
        assertEquals(a.fencingToken(), released.releasedToken());
        assertEquals(clock.nowMillis(), released.releasedAtMillis());

        // 让出立即对实例 2 生效：无有效持有者，本人旧令牌操作被拒（LEASE_RELEASED）
        assertFalse(i2.inspect("job/9").held());
        assertTrue(i2.inspect("job/9").released());
        FenceCheckResult stale = i2.validateProtectedOperation("job/9", "node-A", a.fencingToken());
        assertFalse(stale.accepted());
        assertEquals(RejectReason.LEASE_RELEASED, stale.rejectReason());

        // B 不用等到期，立刻可在实例 2 获取，且令牌严格大于让出前那一枚
        AcquireResult bResult = i2.acquire("job/9", "node-B", 10_000L);
        assertTrue(bResult.granted());
        assertEquals(2L, bResult.grant().fencingToken(), "让出后接管的令牌必须比让出前更大");
        assertFalse(bResult.grant().takeover(), "让出后再获取不是“过期接管”");

        // A 的旧令牌现在被标记为更旧一代；B 本人令牌在实例 1 上可用
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                i1.validateProtectedOperation("job/9", "node-A", a.fencingToken()).rejectReason());
        assertTrue(i1.validateProtectedOperation("job/9", "node-B", 2L).accepted());

        System.out.printf("[TEST] 让出立即生效 => A token=%d 在实例1让出; 实例2立即可见无持有者, 旧令牌=%s; B 立即获取 token=%d(更大) now=%d%n",
                a.fencingToken(), RejectReason.LEASE_RELEASED, bResult.grant().fencingToken(), clock.nowMillis());
    }

    @Test
    void releaseRejectionsAreDistinguishable(@TempDir Path dir) {
        Path stateFile = dir.resolve("shared-state.json");
        LeaseCoordinator c = newInstance(stateFile, "only-instance");
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 不是当前持有者 => NOT_CURRENT_HOLDER（锁明明有效，只是人不对）
        ReleaseResult notHolder = c.release("k", "node-B");
        assertFalse(notHolder.released());
        assertEquals(RejectReason.NOT_CURRENT_HOLDER, notHolder.rejectReason());

        // 锁从未获取 => LOCK_NOT_HELD
        assertEquals(RejectReason.LOCK_NOT_HELD, c.release("missing", "node-A").rejectReason());
        // 非法参数
        assertEquals(RejectReason.INVALID_LOCK_NAME, c.release(" ", "node-A").rejectReason());
        assertEquals(RejectReason.INVALID_HOLDER_ID, c.release("k", " ").rejectReason());

        // 合法让出后再让出 => LEASE_RELEASED
        assertTrue(c.release("k", "node-A").released());
        ReleaseResult again = c.release("k", "node-A");
        assertFalse(again.released());
        assertEquals(RejectReason.LEASE_RELEASED, again.rejectReason());

        // 另一把锁：等到期后再让出 => LEASE_EXPIRED（与上面各种原因可区分）
        LeaseGrant d = c.acquire("k2", "node-D", 1_000L).grant();
        clock.advance(1_000L); // now == expireAt
        ReleaseResult expired = c.release("k2", "node-D");
        assertFalse(expired.released());
        assertEquals(RejectReason.LEASE_EXPIRED, expired.rejectReason());
        // 过期后别人也不能以“不是持有者”来让出：锁已失效
        assertEquals(RejectReason.LEASE_EXPIRED, c.release("k2", "node-E").rejectReason());

        System.out.printf("[TEST] release 拒绝矩阵 => 非持有者=%s 未持有=%s 已让出=%s 已过期=%s 非法参数 全部可区分; A让出令牌=%d D过期令牌=%d%n",
                RejectReason.NOT_CURRENT_HOLDER, RejectReason.LOCK_NOT_HELD, RejectReason.LEASE_RELEASED,
                RejectReason.LEASE_EXPIRED, a.fencingToken(), d.fencingToken());
    }
}
