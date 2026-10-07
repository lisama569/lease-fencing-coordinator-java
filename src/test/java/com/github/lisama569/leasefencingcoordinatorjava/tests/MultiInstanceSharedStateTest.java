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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例共用同一份本地状态：无论请求落到哪个实例，锁归属结论唯一、授予即时可见，
 * 令牌跨实例与“重启”（新建实例）严格递增、绝不重发。
 *
 * <p>多个 {@link LeaseCoordinator} 指向同一个状态文件，模拟同一台机器上多个实例共用状态目录；
 * 它们共享同一个 {@link ManualClock}（同机墙钟一致）。
 */
class MultiInstanceSharedStateTest {

    private LeaseCoordinator newInstance(ManualClock clock, Path stateFile, String tag) {
        LeaseCoordinator c = new LeaseCoordinator(clock, new FileLockStateStore(stateFile));
        System.out.printf("[TEST] 启动实例 tag=%s stateFile=%s%n", tag, stateFile);
        return c;
    }

    @Test
    void grantIsImmediatelyVisibleOnEveryOtherInstance(@TempDir Path dir) {
        Path stateFile = dir.resolve("shared-state.json");
        ManualClock clock = new ManualClock(5_000_000L);
        LeaseCoordinator i1 = newInstance(clock, stateFile, "instance-1");
        LeaseCoordinator i2 = newInstance(clock, stateFile, "instance-2");
        LeaseCoordinator i3 = newInstance(clock, stateFile, "instance-3");

        // 请求落到实例 1：A 拿到锁
        LeaseGrant a = i1.acquire("order/77", "node-A", 10_000L).grant();
        assertEquals(1L, a.fencingToken());

        // 请求随后落到实例 2、3：必须立刻看到同一归属、同一令牌，并把 B 挡在门外
        assertEquals("node-A", i2.inspect("order/77").holderId(), "实例2必须立即读到实例1授予的归属");
        assertEquals(1L, i2.inspect("order/77").fencingToken());
        assertTrue(i3.inspect("order/77").held());
        AcquireResult blocked = i2.acquire("order/77", "node-B", 10_000L);
        assertFalse(blocked.granted());
        assertEquals(RejectReason.HELD_BY_OTHER, blocked.rejectReason());

        // 实例 3 上 A 本人带令牌做受保护操作（不带身份的老用法也要一致）
        assertTrue(i3.validateProtectedOperation("order/77", a.fencingToken()).accepted());

        System.out.printf("[TEST] 授予即时可见 => 实例1授予 holder=node-A token=%d; 实例2读到holder=%s 拒绝B=%s; 实例3 fence=ACCEPT now=%d%n",
                a.fencingToken(), i2.inspect("order/77").holderId(), blocked.rejectReason(), clock.nowMillis());
    }

    @Test
    void concurrentAcquireAcrossInstancesHasExactlyOneWinner(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("shared-state.json");
        ManualClock clock = new ManualClock(6_000_000L);

        int instances = 8;
        int threadsPerInstance = 4;
        List<LeaseCoordinator> coordinators = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            coordinators.add(newInstance(clock, stateFile, "instance-" + i));
        }

        int total = instances * threadsPerInstance;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AcquireResult>> futures = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();

        for (int i = 0; i < instances; i++) {
            final LeaseCoordinator coordinator = coordinators.get(i);
            for (int t = 0; t < threadsPerInstance; t++) {
                final String holder = "caller-" + seq.incrementAndGet();
                futures.add(pool.submit(() -> {
                    start.await();
                    return coordinator.acquire("hot-lock", holder, 30_000L);
                }));
            }
        }
        start.countDown(); // 同时放行 32 个落在 8 个实例上的请求

        int granted = 0;
        int heldByOther = 0;
        String winner = null;
        long winnerToken = -1L;
        for (Future<AcquireResult> f : futures) {
            AcquireResult r = f.get(15, TimeUnit.SECONDS);
            if (r.granted()) {
                granted++;
                winner = r.grant().holderId();
                winnerToken = r.grant().fencingToken();
            } else if (r.rejectReason() == RejectReason.HELD_BY_OTHER) {
                heldByOther++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, granted, "跨多实例并发获取必须恰好一个成功，绝不双持有");
        assertEquals(total - 1, heldByOther, "其余全部必须以 HELD_BY_OTHER 被拒绝");
        assertEquals(1L, winnerToken, "首枚令牌必须为 1");

        // 所有实例的只读视图必须收敛到同一个结论
        for (int i = 0; i < coordinators.size(); i++) {
            assertEquals(winner, coordinators.get(i).inspect("hot-lock").holderId(),
                    "实例 " + i + " 的归属结论必须与唯一赢家一致");
        }

        System.out.printf("[TEST] 多实例并发抢锁 instances=%d callers=%d => 唯一赢家=%s token=%d, HELD_BY_OTHER=%d, 双持有=0%n",
                instances, total, winner, winnerToken, heldByOther);
    }

    @Test
    void tokensNeverReissuedAcrossInstancesAndRestarts(@TempDir Path dir) {
        Path stateFile = dir.resolve("shared-state.json");
        ManualClock clock = new ManualClock(7_000_000L);
        LeaseCoordinator i1 = newInstance(clock, stateFile, "instance-1");
        LeaseCoordinator i2 = newInstance(clock, stateFile, "instance-2");
        LeaseCoordinator i3 = newInstance(clock, stateFile, "instance-3");

        LeaseGrant a = i1.acquire("k", "node-A", 5_000L).grant();
        assertEquals(1L, a.fencingToken());

        clock.advance(5_000L); // A 到期
        LeaseGrant b = i2.acquire("k", "node-B", 5_000L).grant();
        assertEquals(2L, b.fencingToken(), "实例2接管必须发放上一枚 +1 的令牌");

        // B 在实例 3 上续约：续约不得换令牌
        assertEquals(2L, i3.renew("k", "node-B", 8_000L).grant().fencingToken());

        // “重启”：新建实例 4，从同一状态文件恢复
        LeaseCoordinator i4 = newInstance(clock, stateFile, "instance-4-restarted");
        assertEquals("node-B", i4.inspect("k").holderId());
        assertEquals(2L, i4.inspect("k").fencingToken());
        FenceCheckResult oldToken = i4.validateProtectedOperation("k", 1L);
        assertFalse(oldToken.accepted());
        assertEquals(RejectReason.FENCING_TOKEN_SUPERSEDED, oldToken.rejectReason());

        // 重启后的实例接管过期锁，令牌继续递增而不是重发
        clock.advance(9_000L); // B 已过期
        LeaseGrant c = i4.acquire("k", "node-C", 5_000L).grant();
        assertEquals(3L, c.fencingToken(), "换实例/重启后接管绝不能重发历史令牌");

        // 再回到实例 1 做一次过期接管，令牌仍继续递增
        clock.advance(5_000L);
        LeaseGrant d = i1.acquire("k", "node-D", 5_000L).grant();
        assertEquals(4L, d.fencingToken());

        System.out.printf("[TEST] 令牌跨实例/重启不重发 => A=1(实例1) B=2(实例2接管) C=3(重启实例接管) D=4(实例1接管) 严格递增, 旧令牌1=%s now=%d%n",
                RejectReason.FENCING_TOKEN_SUPERSEDED, clock.nowMillis());
    }
}
