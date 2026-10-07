package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.ReleaseResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileChannelInterProcessLock;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主动让出：未到期持有者可提前放弃；让出后立即（跨实例）可获取，新令牌严格更大；
 * 非持有者 / 过期持有者让出被拒，原因与 LOCK_NOT_HELD / LOCK_RELEASED 等互相可区分。
 */
class ReleaseSemanticsTest {

    private final ManualClock clock = new ManualClock(9_000_000L);

    private LeaseCoordinator coordinator(Path stateFile) {
        return new LeaseCoordinator(clock, new FileLockStateStore(stateFile),
                new FileChannelInterProcessLock(
                        stateFile.toAbsolutePath().resolveSibling("state.json.lock")));
    }

    @Test
    void releaseMakesLockImmediatelyAcquirableWithGreaterToken(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator c = coordinator(stateFile);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();
        assertEquals(1L, a.fencingToken());

        clock.advance(1_000L); // 远未到期
        ReleaseResult released = c.release("k", "node-A");
        assertTrue(released.released());
        assertEquals(a.fencingToken(), released.releasedToken());

        // 让出后、被接管前的窗口里：旧令牌仍是当前代，但锁已让出 => LOCK_RELEASED（与过期/被取代可区分）
        assertEquals(RejectReason.LOCK_RELEASED,
                c.validateProtectedOperation("k", "node-A", a.fencingToken()).rejectReason());

        // 让出后立即可以被别的调用方获取，且令牌更大
        LeaseGrant b = c.acquire("k", "node-B", 5_000L).grant();
        assertEquals("node-B", b.holderId());
        assertEquals(a.fencingToken() + 1L, b.fencingToken(), "让出后重新授予必须发放更大令牌");

        // B 接管后 A 的旧令牌转为更早一代 => SUPERSEDED，B 本人新令牌放行
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                c.validateProtectedOperation("k", "node-A", a.fencingToken()).rejectReason());
        assertTrue(c.validateProtectedOperation("k", "node-B", b.fencingToken()).accepted());

        System.out.printf("[TEST] 让出 => holder=node-A releasedToken=%d 提前让出; node-B接管token=%d now=%d%n",
                a.fencingToken(), b.fencingToken(), clock.nowMillis());
    }

    @Test
    void releaseTakesEffectImmediatelyAcrossInstances(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator i1 = coordinator(stateFile);
        LeaseCoordinator i2 = coordinator(stateFile);

        LeaseGrant a = i1.acquire("k", "node-A", 5_000L).grant();
        // 在让出前，i2 上 B 拿不到
        assertEquals(RejectReason.HELD_BY_OTHER, i2.acquire("k", "node-B", 5_000L).rejectReason());

        // A 在实例1 上主动让出
        assertTrue(i1.release("k", "node-A").released());

        // 实例2 必须立刻看到锁已空闲：B 立即拿到，且令牌更大
        AcquireResult onI2 = i2.acquire("k", "node-B", 5_000L);
        assertTrue(onI2.granted(), "让出必须跨实例即时生效，B 应能立即获取");
        assertEquals(a.fencingToken() + 1L, onI2.grant().fencingToken());

        System.out.printf("[TEST] 让出跨实例生效 => i1:node-A release token=%d; i2:node-B 立即获取 token=%d%n",
                a.fencingToken(), onI2.grant().fencingToken());
    }

    @Test
    void releaseRejectsAreDistinguishable(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator c = coordinator(stateFile);
        c.acquire("k", "node-A", 5_000L);

        // 从未持有的锁
        assertEquals(RejectReason.LOCK_NOT_HELD, c.release("missing", "node-A").rejectReason());
        // 参数非法
        assertEquals(RejectReason.INVALID_LOCK_NAME, c.release("  ", "node-A").rejectReason());
        assertEquals(RejectReason.INVALID_HOLDER_ID, c.release("k", " ").rejectReason());
        // 未到期但不是当前持有者
        ReleaseResult notHolder = c.release("k", "node-B");
        assertFalse(notHolder.released());
        assertEquals(RejectReason.NOT_CURRENT_HOLDER, notHolder.rejectReason());

        // 合法让出后：同一个人再让、别人再让都属 NOT_CURRENT_HOLDER（锁已无有效持有者）
        assertTrue(c.release("k", "node-A").released());
        assertEquals(RejectReason.NOT_CURRENT_HOLDER, c.release("k", "node-A").rejectReason());
        assertEquals(RejectReason.NOT_CURRENT_HOLDER, c.release("k", "node-B").rejectReason());

        // 过期持有者不能主动让出：新锁，A 取得后到期
        LeaseCoordinator c2 = coordinator(dir.resolve("other.json"));
        c2.acquire("k2", "node-A", 1_000L);
        clock.advance(2_000L);
        ReleaseResult expired = c2.release("k2", "node-A");
        assertFalse(expired.released());
        assertEquals(RejectReason.LEASE_EXPIRED, expired.rejectReason(),
                "过期持有者身份已失效，拒绝原因必须是 LEASE_EXPIRED 而非 NOT_CURRENT_HOLDER");
        // 过期后别人也不能“替他让出”
        assertEquals(RejectReason.LEASE_EXPIRED, c2.release("k2", "node-B").rejectReason());

        System.out.printf("[TEST] 让出拒绝原因 => 未持有=%s 非持有者=%s 已让出=%s 已过期=%s 均可区分%n",
                RejectReason.LOCK_NOT_HELD, RejectReason.NOT_CURRENT_HOLDER,
                RejectReason.NOT_CURRENT_HOLDER, RejectReason.LEASE_EXPIRED);
    }

    @Test
    void releasedLockCannotBeRenewedButIsReacquirable(@TempDir Path dir) {
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator c = coordinator(stateFile);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();
        assertTrue(c.release("k", "node-A").released());

        // 让出后续约被拒（身份还在，但锁已让出）
        assertEquals(RejectReason.LOCK_RELEASED, c.renew("k", "node-A", 5_000L).rejectReason());
        assertFalse(c.inspect("k").held(), "让出后快照不应仍判为有效持有");
        assertTrue(c.inspect("k").released());

        // 原持有者自己也能立即重新获取，拿到更大令牌
        LeaseGrant again = c.acquire("k", "node-A", 5_000L).grant();
        assertEquals(a.fencingToken() + 1L, again.fencingToken());

        System.out.printf("[TEST] 让出后 => renew=%s held=false released=true; 原持有者重新获取 token=%d%n",
                RejectReason.LOCK_RELEASED, again.fencingToken());
    }
}
