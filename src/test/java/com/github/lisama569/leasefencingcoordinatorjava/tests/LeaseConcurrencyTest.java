package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.lease.Lease;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseException;
import com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.support.FakeClock;
import com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrent acquisition must never produce two valid holders. */
class LeaseConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseConcurrencyTest.class);

    private static final int CALLERS = 32;

    @TempDir
    Path storeDir;

    @Test
    void racingAcquirersProduceExactlyOneHolderAndDistinguishableRejections()
            throws Exception {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CyclicBarrier start = new CyclicBarrier(CALLERS);
        CountDownLatch done = new CountDownLatch(CALLERS);
        Set<String> winners = ConcurrentHashMap.newKeySet();
        Set<Long> grantedTokens = ConcurrentHashMap.newKeySet();
        Set<RejectReason> rejectReasons = ConcurrentHashMap.newKeySet();

        try {
            List<Future<?>> futures = IntStream.range(0, CALLERS)
                    .mapToObj(i -> (Runnable) () -> {
                        String caller = "caller-" + i;
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            Lease lease = coordinator.acquire("hot-lock", caller, 10_000);
                            winners.add(caller);
                            grantedTokens.add(lease.fencingToken());
                            log.info("caller={} WON holder={} token={} now={} expiresAt={}",
                                    caller, lease.holderId(), lease.fencingToken(),
                                    clock.nowMillis(), lease.expiresAtMs());
                        } catch (LeaseException ex) {
                            rejectReasons.add(ex.getReason());
                            log.info("caller={} LOST reason={} now={}",
                                    caller, ex.getReason(), clock.nowMillis());
                        } catch (Exception ex) {
                            log.error("caller={} unexpected failure", caller, ex);
                            throw new RuntimeException(ex);
                        } finally {
                            done.countDown();
                        }
                    })
                    .map(pool::submit)
                    .toList();

            assertTrue(done.await(10, TimeUnit.SECONDS), "all racing calls must finish");
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("race result winners={} winnerCount={} rejectReasons={}",
                winners, winners.size(), rejectReasons);
        assertEquals(1, winners.size(), "exactly one caller must hold the lock");
        assertEquals(Set.of(1L), new HashSet<>(grantedTokens),
                "only token 1 may have been granted during the race");
        assertEquals(Set.of(RejectReason.LOCK_HELD_BY_OTHER), rejectReasons,
                "every loser must be rejected with LOCK_HELD_BY_OTHER");
    }
}
