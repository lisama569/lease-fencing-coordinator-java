package com.github.lisama569.leasefencingcoordinatorjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真正跨 OS 进程的集成验证：每个“实例”是一个独立 JVM 子进程，
 * 它们指向同一个本地状态文件，验证跨进程文件锁下的归属唯一性、授予可见性、
 * 令牌不重发、身份校验与主动让出。
 *
 * <p>子进程入口见 {@link com.github.lisama569.leasefencingcoordinatorjava.tests.support.LeaseCliClient}。
 */
class MultiProcessCoordinatorTest {

    private String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 启动一个实例子进程执行一条命令，返回合并输出（含 RESULT 行）。 */
    private String runInstance(Path stateFile, String cmd, String lockName, String... rest) throws Exception {
        String classpath = System.getProperty("java.class.path");
        List<String> args = new ArrayList<>();
        args.add(lockName);
        args.addAll(List.of(rest));
        List<String> command = buildCommand(stateFile, cmd, args, classpath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "子进程超时: " + cmd + "\n" + output);
        assertEquals(0, p.exitValue(), "子进程异常退出:\n" + output);
        return output;
    }

    private List<String> buildCommand(Path stateFile, String cmd, List<String> rest, String classpath) {
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-cp");
        command.add(classpath);
        command.add("com.github.lisama569.leasefencingcoordinatorjava.tests.support.LeaseCliClient");
        command.add(cmd);
        command.add(stateFile.toString());
        command.addAll(rest);
        return command;
    }

    private Process startInstance(Path stateFile, Path signal, String holder) throws Exception {
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-cp");
        command.add(classpath);
        command.add("com.github.lisama569.leasefencingcoordinatorjava.tests.support.LeaseCliClient");
        command.add("acquire-wait");
        command.add(stateFile.toString());
        command.add("mp-lock");
        command.add(holder);
        command.add("60000");
        command.add(signal.toString());
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private Map<String, String> parseResult(String output) {
        String line = output.lines().filter(l -> l.startsWith("RESULT ")).findFirst()
                .orElseThrow(() -> new AssertionError("子进程未输出 RESULT:\n" + output));
        Map<String, String> kv = new java.util.HashMap<>();
        for (String pair : line.substring("RESULT ".length()).split(" ")) {
            int eq = pair.indexOf('=');
            kv.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return kv;
    }

    @Test
    void separateJvmProcessesShareOneStateWithUniqueOwnerAndMonotonicTokens(@TempDir Path dir) throws Exception {
        Path stateFile = dir.resolve("mp-state.json");
        Path signal = dir.resolve("start.signal");

        // 进程 P1 首次获取
        Map<String, String> p1 = parseResult(runInstance(stateFile, "acquire", "mp-lock", "P1", "60000"));
        assertEquals("true", p1.get("granted"));
        assertEquals("1", p1.get("token"));

        // 另一个进程 P2 立刻尝试获取（锁有效）=> 必须看到 P1 的授予并被拒
        Map<String, String> p2Blocked = parseResult(runInstance(stateFile, "acquire", "mp-lock", "P2", "60000"));
        assertEquals("false", p2Blocked.get("granted"));
        assertEquals("HELD_BY_OTHER", p2Blocked.get("reason"), "另一进程必须读到 P1 的归属");

        // 两个全新进程等信号后同时抢锁（锁仍归 P1）：两者都应被拒，证明跨进程下没有任何双授予
        Process a = startInstance(stateFile, signal, "P3");
        Process b = startInstance(stateFile, signal, "P4");
        Thread.sleep(500L); // 让两个进程都进入等待
        Files.createFile(signal);
        String outA = new String(a.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String outB = new String(b.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(a.waitFor(30, TimeUnit.SECONDS) && b.waitFor(30, TimeUnit.SECONDS), "并发子进程超时");
        Map<String, String> rA = parseResult(outA);
        Map<String, String> rB = parseResult(outB);
        assertEquals("false", rA.get("granted"), "P3 不能在 P1 未到期时拿锁");
        assertEquals("false", rB.get("granted"), "P4 不能在 P1 未到期时拿锁");
        assertEquals("HELD_BY_OTHER", rA.get("reason"));
        assertEquals("HELD_BY_OTHER", rB.get("reason"));

        // 身份校验：P2 冒充 P1 拿令牌 1 => HOLDER_MISMATCH；P1 本人 => accepted
        assertEquals("HOLDER_MISMATCH",
                parseResult(runInstance(stateFile, "fence", "mp-lock", "P2", "1")).get("reason"));
        assertEquals("true",
                parseResult(runInstance(stateFile, "fence", "mp-lock", "P1", "1")).get("accepted"));

        // P1 在另一个进程里主动让出，立刻对其他进程生效
        Map<String, String> released = parseResult(runInstance(stateFile, "release", "mp-lock", "P1"));
        assertEquals("true", released.get("released"));
        assertEquals("1", released.get("token"));

        // 非持有者让出 => NOT_CURRENT_HOLDER（此刻锁已让出，P2 再来让出 => LEASE_RELEASED）
        assertEquals("LEASE_RELEASED",
                parseResult(runInstance(stateFile, "release", "mp-lock", "P2")).get("reason"));

        // 让出后立即被新进程获取，令牌必须更大（2），且不是过期等待
        Map<String, String> p2Grant = parseResult(runInstance(stateFile, "acquire", "mp-lock", "P2", "60000"));
        assertEquals("true", p2Grant.get("granted"));
        assertEquals("2", p2Grant.get("token"), "让出后接管令牌必须严格递增");

        // P1 持旧令牌 => 已被新一代取代
        assertEquals("FENCING_TOKEN_SUPERSEDED",
                parseResult(runInstance(stateFile, "fence", "mp-lock", "P1", "1")).get("reason"));
        // P2 本人持新令牌 => accepted
        assertEquals("true",
                parseResult(runInstance(stateFile, "fence", "mp-lock", "P2", "2")).get("accepted"));

        System.out.printf("[TEST] 跨进程共享状态 => P1=1; P2并发=%s; P3/P4同时抢=%s,%s; 冒充=%s; P1让出; P2接管=2; 旧令牌=%s%n",
                p2Blocked.get("reason"), rA.get("reason"), rB.get("reason"),
                "HOLDER_MISMATCH", "FENCING_TOKEN_SUPERSEDED");
    }
}
