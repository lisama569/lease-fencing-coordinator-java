package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileChannelInterProcessLock;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.tests.support.ManualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例共用同一份状态目录：归属结论全局唯一、授予即时可见、令牌跨实例/重启不重发、
 * 跨实例并发抢锁恰好一个持有者。
 *
 * <p>同一 JVM 内构造多个协调器、传入同一个状态文件与同一锁文件路径，模拟“多个实例共用状态”；
 * 跨 JVM 的串行化由同一套文件锁在真实多进程时同样保证。
 */
class MultiInstanceCoordinatorTest {

    /** 用同一个时钟与状态文件构造一个新的“实例”（锁通道按路径在 JVM 内共享）。 */
    private LeaseCoordinator instance(ManualClock clock, Path stateFile) {
        return new LeaseCoordinator(clock, new FileLockStateStore(stateFile),
                new FileChannelInterProcessLock(
                        stateFile.toAbsolutePath().resolveSibling("state.json.lock")));
    }

    @Test
    void grantOnOneInstanceIsImmediatelyVisibleOnOthers(@TempDir Path dir) {
        ManualClock clock = new ManualClock();
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator i1 = instance(clock, stateFile);
        LeaseCoordinator i2 = instance(clock, stateFile);
        LeaseCoordinator i3 = instance(clock, stateFile);

        LeaseGrant a = i1.acquire("k", "node-A", 5_000L).grant();
        assertEquals(1L, a.fencingToken());

        // 授予立刻对其他实例可见：别的实例上的竞争请求必须被 HELD_BY_OTHER 挡掉，绝不双授予
        AcquireResult onI2 = i2.acquire("k", "node-B", 5_000L);
        assertFalse(onI2.granted(), "别的实例必须立刻看到 A 已持锁");
        assertEquals(RejectReason.HELD_BY_OTHER, onI2.rejectReason());
        assertEquals("node-A", i3.inspect("k").holderId(), "所有实例对归属结论必须一致");
        assertEquals(1L, i3.inspect("k").fencingToken());
        assertTrue(i2.validateProtectedOperation("k", "node-A", 1L).accepted(),
                "当前持有者本人 + 当前令牌 + 未到期，换个实例校验也必须放行");

        // 在另一实例上续约：新到期时间必须立刻被第三个实例看到
        clock.advance(1_000L);
        assertTrue(i2.renew("k", "node-A", 10_000L).renewed());
        assertEquals(clock.nowMillis() + 10_000L, i3.inspect("k").expireAtMillis(),
                "续约结论必须跨实例即时一致");

        System.out.printf("[TEST] 多实例即时可见 => i1授予 holder=node-A token=%d; i2竞争=%s; i3看到holder=%s; i2续约后i3到期=%d now=%d%n",
                a.fencingToken(), RejectReason.HELD_BY_OTHER, i3.inspect("k").holderId(),
                i3.inspect("k").expireAtMillis(), clock.nowMillis());
    }

    @Test
    void exactlyOneWinnerWhenInstancesCompeteConcurrently(@TempDir Path dir) throws Exception {
        ManualClock clock = new ManualClock();
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator i1 = instance(clock, stateFile);
        LeaseCoordinator i2 = instance(clock, stateFile);

        int perInstance = 16;
        ExecutorService pool = Executors.newFixedThreadPool(perInstance * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AcquireResult>> futures = new ArrayList<>();

        for (int i = 0; i < perInstance; i++) {
            final String holder1 = "node-A-" + i;
            final String holder2 = "node-B-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return i1.acquire("hot-lock", holder1, 5_000L);
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return i2.acquire("hot-lock", holder2, 5_000L);
            }));
        }
        start.countDown();

        int granted = 0;
        int heldByOther = 0;
        String winner = null;
        Long winnerToken = null;
        for (Future<AcquireResult> f : futures) {
            AcquireResult r = f.get(10, TimeUnit.SECONDS);
            if (r.granted()) {
                granted++;
                winner = r.grant().holderId();
                winnerToken = r.grant().fencingToken();
            } else if (r.rejectReason() == RejectReason.HELD_BY_OTHER) {
                heldByOther++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, granted, "跨实例并发获取必须恰好一个成功持有者");
        assertEquals(perInstance * 2 - 1, heldByOther, "其余全部必须以 HELD_BY_OTHER 被拒绝");
        assertEquals(1L, winnerToken);
        assertEquals(winner, i1.inspect("hot-lock").holderId(), "实例1 看到的归属必须是唯一持有者");
        assertEquals(winner, i2.inspect("hot-lock").holderId(), "实例2 看到的归属必须是唯一持有者");

        System.out.printf("[TEST] 跨实例并发竞争 threads=%d => 唯一持有者=%s token=%d, HELD_BY_OTHER=%d, 双持有=0%n",
                perInstance * 2, winner, winnerToken, heldByOther);
    }

    @Test
    void tokensAreGloballyMonotonicAcrossInstancesAndRestart(@TempDir Path dir) {
        ManualClock clock = new ManualClock(5_000_000L);
        Path stateFile = dir.resolve("state.json");
        LeaseCoordinator i1 = instance(clock, stateFile);
        LeaseCoordinator i2 = instance(clock, stateFile);

        // 实例1 发 token=1；实例2 看到后不得重发 1
        LeaseGrant a = i1.acquire("k", "node-A", 5_000L).grant();
        assertEquals(1L, a.fencingToken());
        clock.advance(5_000L); // A 恰好到期
        // 过期后在实例2 上接管，必须发 2 而不是重发 1
        LeaseGrant b = i2.acquire("k", "node-B", 5_000L).grant();
        assertEquals(2L, b.fencingToken(), "跨实例接管令牌必须接续，不得重发");
        assertTrue(b.takeover());

        // “重启”：用同一状态文件再构造一个全新实例
        LeaseCoordinator restarted = instance(clock, stateFile);
        clock.advance(5_000L); // B 到期
        LeaseGrant c = restarted.acquire("k", "node-C", 5_000L).grant();
        assertEquals(3L, c.fencingToken(), "重启后接管必须发 3，历史令牌绝不重发");

        // 任何实例上都不能再用旧令牌通过
        FenceCheckResult stale = i1.validateProtectedOperation("k", "node-A", 1L);
        assertFalse(stale.accepted());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED, stale.rejectReason());
        FenceCheckResult unknown = i2.validateProtectedOperation("k", "node-X", 99L);
        assertEquals(RejectReason.UNKNOWN_FENCING_TOKEN, unknown.rejectReason());

        System.out.printf("[TEST] 令牌全局连续 => i1=%d(A) i2接管=%d(B) 重启实例接管=%d(C) 旧令牌=%s 未知令牌=%s%n",
                a.fencingToken(), b.fencingToken(), c.fencingToken(),
                RejectReason.FENCING_TOKEN_SUPERSEDED, RejectReason.UNKNOWN_FENCING_TOKEN);
    }
}
