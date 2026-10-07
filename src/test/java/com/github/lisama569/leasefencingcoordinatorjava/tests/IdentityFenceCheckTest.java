package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
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
 * 受保护操作的身份校验：只有“当前令牌对应的持有者本人 + 租约未到期”才放行。
 * 人不对（HOLDER_MISMATCH）必须与过期、已让出、被取代、从未发放、未持有、非法参数互相可区分。
 */
class IdentityFenceCheckTest {

    private final ManualClock clock = new ManualClock(8_000_000L);

    private LeaseCoordinator coordinator(Path dir) {
        return new LeaseCoordinator(clock, new FileLockStateStore(dir.resolve("state.json")));
    }

    @Test
    void onlyTokenOwnerIsAcceptedWhileLeaseValid(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 持有者本人 + 当前令牌 + 未到期 => 放行
        FenceCheckResult self = c.validateProtectedOperation("k", "node-A", a.fencingToken());
        assertTrue(self.accepted());
        assertEquals("node-A", self.presentedHolder());

        // 别人拿到同一枚令牌：令牌数字正确但人不对 => 必须拒绝，且原因独立
        FenceCheckResult impostor = c.validateProtectedOperation("k", "node-X", a.fencingToken());
        assertFalse(impostor.accepted());
        assertEquals(RejectReason.HOLDER_MISMATCH, impostor.rejectReason());
        assertEquals(a.fencingToken(), impostor.presentedToken());
        assertEquals(a.fencingToken(), impostor.currentToken(), "令牌其实是当前最新一代，纯粹是人不对");

        System.out.printf("[TEST] identity fence => 本人node-A token=%d => ACCEPT; 冒充者node-X 同一枚token => %s now=%d%n",
                a.fencingToken(), impostor.rejectReason(), clock.nowMillis());
    }

    @Test
    void holderMismatchIsDistinguishableFromOtherRejections(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 1) 人不对（租约仍有效、令牌仍当前）
        assertEquals(RejectReason.HOLDER_MISMATCH,
                c.validateProtectedOperation("k", "node-X", a.fencingToken()).rejectReason());

        // 2) 过期：本人持当前令牌，但 now == expireAt
        clock.advance(5_000L);
        FenceCheckResult expired = c.validateProtectedOperation("k", "node-A", a.fencingToken());
        assertFalse(expired.accepted());
        assertEquals(RejectReason.LEASE_EXPIRED, expired.rejectReason(),
                "本人 + 当前令牌 + 已过期 => LEASE_EXPIRED，与 HOLDER_MISMATCH 可区分");

        // 3) 从未发放过的（更大的）令牌
        assertEquals(RejectReason.UNKNOWN_FENCING_TOKEN,
                c.validateProtectedOperation("k", "node-A", a.fencingToken() + 10L).rejectReason());

        // 4) 接管后：旧持有者本人拿旧令牌 => 已被新一代取代（而不是身份不匹配）
        LeaseGrant b = c.acquire("k", "node-B", 5_000L).grant();
        assertEquals(2L, b.fencingToken());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                c.validateProtectedOperation("k", "node-A", a.fencingToken()).rejectReason());
        // 新持有者本人放行；别人冒充新持有者 => 仍是身份不匹配
        assertTrue(c.validateProtectedOperation("k", "node-B", b.fencingToken()).accepted());
        assertEquals(RejectReason.HOLDER_MISMATCH,
                c.validateProtectedOperation("k", "node-A", b.fencingToken()).rejectReason(),
                "新令牌 + 非持有者 => HOLDER_MISMATCH，与 SUPERSEDED 可区分");

        // 5) 锁从未获取
        assertEquals(RejectReason.LOCK_NOT_HELD,
                c.validateProtectedOperation("nope", "node-A", 1L).rejectReason());
        // 6) 非法令牌 / 非法身份 / 非法锁名
        assertEquals(RejectReason.INVALID_FENCING_TOKEN,
                c.validateProtectedOperation("k", "node-B", 0L).rejectReason());
        assertEquals(RejectReason.INVALID_HOLDER_ID,
                c.validateProtectedOperation("k", "  ", b.fencingToken()).rejectReason());
        assertEquals(RejectReason.INVALID_LOCK_NAME,
                c.validateProtectedOperation(" ", "node-B", b.fencingToken()).rejectReason());

        System.out.printf("[TEST] 拒绝原因矩阵 => 人不对=%s 过期=%s 未知=%s 被取代=%s 未持有=%s 非法令牌/身份/锁名 全部可区分 now=%d%n",
                RejectReason.HOLDER_MISMATCH, RejectReason.LEASE_EXPIRED, RejectReason.UNKNOWN_FENCING_TOKEN,
                RejectReason.FENCING_TOKEN_SUPERSEDED, RejectReason.LOCK_NOT_HELD, clock.nowMillis());
    }

    @Test
    void legacyTwoArgCheckRemainsBackwardCompatible(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 老用法（不传身份）：只看令牌与有效期，保持兼容
        assertTrue(c.validateProtectedOperation("k", a.fencingToken()).accepted());
        clock.advance(5_000L);
        assertFalse(c.validateProtectedOperation("k", a.fencingToken()).accepted());
        assertEquals(RejectReason.LEASE_EXPIRED,
                c.validateProtectedOperation("k", a.fencingToken()).rejectReason());

        System.out.println("[TEST] 老用法 validateProtectedOperation(lockName, token) 语义保持兼容");
    }
}
