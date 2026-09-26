package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发竞争：多线程同时抢同一把锁，恰好一个成功，其余全部 HELD_BY_OTHER，绝不双持有。
 */
class ConcurrentContentionTest {

    @Test
    void exactlyOneWinnerUnderConcurrentAcquire(@TempDir Path dir) throws Exception {
        ManualClock clock = new ManualClock();
        LeaseCoordinator c = new LeaseCoordinator(clock, new FileLockStateStore(dir.resolve("state.json")));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AcquireResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String holder = "node-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return c.acquire("hot-lock", holder, 5_000L);
            }));
        }
        start.countDown(); // 同时放行

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

        assertEquals(1, granted, "并发获取必须恰好一个成功持有者");
        assertEquals(threads - 1, heldByOther, "其余全部必须以 HELD_BY_OTHER 被拒绝");
        assertEquals(1L, winnerToken, "首个令牌为 1");

        // 状态层只能看到同一个持有者，进一步确认无双持有
        assertEquals(winner, c.inspect("hot-lock").holderId());
        assertTrue(c.inspect("hot-lock").held());

        System.out.printf("[TEST] 并发竞争 threads=%d => 唯一持有者=%s token=%d, HELD_BY_OTHER 拒绝数=%d, 双持有=0%n",
                threads, winner, winnerToken, heldByOther);
    }
}
