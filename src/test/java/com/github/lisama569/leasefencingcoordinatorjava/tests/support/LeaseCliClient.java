package com.github.lisama569.leasefencingcoordinatorjava.tests.support;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.ReleaseResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.SystemClock;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 极简命令行客户端：每个 JVM 进程代表一个“实例”，用系统墙钟对共享状态文件做一次操作。
 * 仅供跨进程集成测试 {@code MultiProcessCoordinatorTest} 启动子进程使用。
 *
 * <p>输出单行：{@code RESULT key=value ...}，父进程据此断言。
 */
public final class LeaseCliClient {

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        switch (cmd) {
            case "acquire" -> doAcquire(args[1], args[2], args[3], Long.parseLong(args[4]), null);
            case "acquire-wait" -> {
                // 等待信号文件出现后再发起获取，用于让两个进程尽量同时抢锁
                Path signal = Path.of(args[5]);
                waitForSignal(signal, 10_000L);
                doAcquire(args[1], args[2], args[3], Long.parseLong(args[4]), signal);
            }
            case "release" -> doRelease(args[1], args[2], args[3]);
            case "fence" -> doFence(args[1], args[2], args[3], Long.parseLong(args[4]));
            default -> throw new IllegalArgumentException("unknown cmd: " + cmd);
        }
    }

    private static void waitForSignal(Path signal, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!Files.exists(signal) && System.currentTimeMillis() < deadline) {
            Thread.sleep(2L);
        }
        if (!Files.exists(signal)) {
            throw new IllegalStateException("等待启动信号超时: " + signal);
        }
    }

    private static void doAcquire(String stateFile, String lockName, String holder, long ttl, Path signal) {
        LeaseCoordinator c = new LeaseCoordinator(new SystemClock(), new FileLockStateStore(Path.of(stateFile)));
        AcquireResult r = c.acquire(lockName, holder, ttl);
        long token = r.grant() != null ? r.grant().fencingToken() : -1L;
        System.out.printf("RESULT granted=%s token=%d reason=%s holder=%s%n",
                r.granted(), token, r.rejectReason(), holder);
        System.out.flush();
    }

    private static void doRelease(String stateFile, String lockName, String holder) {
        LeaseCoordinator c = new LeaseCoordinator(new SystemClock(), new FileLockStateStore(Path.of(stateFile)));
        ReleaseResult r = c.release(lockName, holder);
        System.out.printf("RESULT released=%s token=%d reason=%s holder=%s%n",
                r.released(), r.releasedToken(), r.rejectReason(), holder);
        System.out.flush();
    }

    private static void doFence(String stateFile, String lockName, String holder, long token) {
        LeaseCoordinator c = new LeaseCoordinator(new SystemClock(), new FileLockStateStore(Path.of(stateFile)));
        FenceCheckResult r = c.validateProtectedOperation(lockName, holder, token);
        System.out.printf("RESULT accepted=%s reason=%s holder=%s token=%d%n",
                r.accepted(), r.rejectReason(), holder, token);
        System.out.flush();
    }

    private LeaseCliClient() {
    }
}
