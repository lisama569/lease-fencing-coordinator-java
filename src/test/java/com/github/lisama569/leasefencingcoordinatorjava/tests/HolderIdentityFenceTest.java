package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
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
 * 受保护操作的调用方身份校验：只有“当前这枚令牌对应的持有者本人 + 租约未到期”才放行；
 * 换个人拿同一枚令牌必须以 HOLDER_IDENTITY_MISMATCH 拒绝，且与过期、被取代、未发放、让出互相可区分。
 */
class HolderIdentityFenceTest {

    private final ManualClock clock = new ManualClock(7_000_000L);

    private LeaseCoordinator coordinator(Path dir) {
        Path stateFile = dir.resolve("state.json");
        return new LeaseCoordinator(clock, new FileLockStateStore(stateFile),
                new FileChannelInterProcessLock(stateFile.toAbsolutePath().resolveSibling("state.json.lock")));
    }

    @Test
    void onlyHolderItselfPassesWithCurrentToken(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 本人 + 当前令牌 + 未到期 => 放行
        FenceCheckResult ok = c.validateProtectedOperation("k", "node-A", a.fencingToken());
        assertTrue(ok.accepted());
        assertEquals("node-A", ok.presentedHolder());

        // 另一个调用方拿着同一枚（数字正确的）令牌 => 人不对，必须拒绝且原因可区分
        FenceCheckResult wrongPerson = c.validateProtectedOperation("k", "node-B", a.fencingToken());
        assertFalse(wrongPerson.accepted());
        assertEquals(RejectReason.HOLDER_IDENTITY_MISMATCH, wrongPerson.rejectReason());
        assertEquals(a.fencingToken(), wrongPerson.presentedToken());
        assertEquals(a.fencingToken(), wrongPerson.currentToken(), "令牌数字其实是对的，纯粹身份不符");

        // 身份非法（null/空白）单独可区分
        assertEquals(RejectReason.INVALID_HOLDER_ID,
                c.validateProtectedOperation("k", "  ", a.fencingToken()).rejectReason());

        System.out.printf("[TEST] 身份校验 => 本人=ACCEPT; 他人持同令牌=%s(当前令牌=%d) now=%d%n",
                RejectReason.HOLDER_IDENTITY_MISMATCH, a.fencingToken(), clock.nowMillis());
    }

    @Test
    void identityRejectIsDistinguishableFromExpirySupersededAndUnknown(@TempDir Path dir) {
        LeaseCoordinator c = coordinator(dir);
        LeaseGrant a = c.acquire("k", "node-A", 5_000L).grant();

        // 1) 人不对（令牌对、未过期）
        assertEquals(RejectReason.HOLDER_IDENTITY_MISMATCH,
                c.validateProtectedOperation("k", "node-B", a.fencingToken()).rejectReason());

        // 2) 令牌压根没发过（过大）
        assertEquals(RejectReason.UNKNOWN_FENCING_TOKEN,
                c.validateProtectedOperation("k", "node-A", a.fencingToken() + 5L).rejectReason());

        // 3) 本人 + 当前令牌，但租约到期 => LEASE_EXPIRED（不是身份问题）
        clock.advance(5_000L); // now == expireAt
        assertEquals(RejectReason.LEASE_EXPIRED,
                c.validateProtectedOperation("k", "node-A", a.fencingToken()).rejectReason());
        // 已过期时他人来，也应归为 LEASE_EXPIRED（先过期后身份），与身份不符区分开
        assertEquals(RejectReason.LEASE_EXPIRED,
                c.validateProtectedOperation("k", "node-B", a.fencingToken()).rejectReason());

        // 4) B 接管后 A 的令牌成为更早一代 => SUPERSEDED（即使 A 本人来）
        LeaseGrant b = c.acquire("k", "node-B", 5_000L).grant();
        assertEquals(a.fencingToken() + 1L, b.fencingToken());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED,
                c.validateProtectedOperation("k", "node-A", a.fencingToken()).rejectReason());
        // B 本人持新令牌 => 放行
        assertTrue(c.validateProtectedOperation("k", "node-B", b.fencingToken()).accepted());
        // A 拿着 B 的令牌数字（非本人、当前代）=> 身份不符
        assertEquals(RejectReason.HOLDER_IDENTITY_MISMATCH,
                c.validateProtectedOperation("k", "node-A", b.fencingToken()).rejectReason());

        System.out.printf("[TEST] 拒绝原因可区分 => 人不对=%s 未发放=%s 已过期=%s 被取代=%s; tokenA=%d tokenB=%d%n",
                RejectReason.HOLDER_IDENTITY_MISMATCH, RejectReason.UNKNOWN_FENCING_TOKEN,
                RejectReason.LEASE_EXPIRED, RejectReason.FENCING_TOKEN_SUPERSEDED,
                a.fencingToken(), b.fencingToken());
    }
}
