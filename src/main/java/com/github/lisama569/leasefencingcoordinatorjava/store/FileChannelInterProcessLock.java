package com.github.lisama569.leasefencingcoordinatorjava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 基于 {@link FileChannel#lock()} 的跨 JVM 排他锁实现。
 *
 * <p>两层串行化：
 * <ol>
 *   <li><b>JVM 内监视器</b>：锁通道按锁文件的绝对路径在静态注册表中共享，同一 JVM 内、
 *       指向同一状态目录的多个协调器实例先在同一把 {@link ReentrantLock} 上排队。这既保证了
 *       同 JVM 多实例测试（多实例共用一个状态文件）的正确性，也避免同通道重叠文件锁抛出
 *       {@link OverlappingFileLockException}。</li>
 *   <li><b>跨 JVM 文件锁</b>：拿到 JVM 内监视器后再对锁文件持有进程级排他锁，
 *       从而把不同操作系统进程的“读状态 → 判定 → 写状态”串行化。</li>
 * </ol>
 *
 * <p>锁通道随 JVM 生命周期保持打开；状态文件本身仍由 {@link FileLockStateStore} 走
 * “临时文件 + 原子 rename”，锁文件永不 rename、永不删除。
 */
public class FileChannelInterProcessLock implements InterProcessLock {

    private static final Logger log = LoggerFactory.getLogger(FileChannelInterProcessLock.class);

    /** key = 锁文件绝对路径；同一 JVM 内同路径的多个实例共享同一通道与监视器。 */
    private static final Map<Path, SharedEntry> REGISTRY = new ConcurrentHashMap<>();

    private final SharedEntry entry;

    public FileChannelInterProcessLock(Path lockFile) {
        Path key = lockFile.toAbsolutePath().normalize();
        this.entry = REGISTRY.computeIfAbsent(key, FileChannelInterProcessLock::open);
    }

    private static SharedEntry open(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(path, CREATE, READ, WRITE);
            log.info("跨实例文件锁通道已打开 lockFile={}", path);
            return new SharedEntry(channel);
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开跨实例锁文件: " + path, e);
        }
    }

    @Override
    public void lock() {
        // 先拿 JVM 内监视器，保证同一 JVM 内多实例也完全串行
        entry.monitor.lock();
        boolean fileLocked = false;
        try {
            try {
                entry.fileLock = entry.channel.lock();
            } catch (OverlappingFileLockException e) {
                // 理论上被 JVM 内监视器挡住了；抛出即说明存在协调器之外的同通道加锁，属于使用错误
                throw new IllegalStateException("同一 JVM 内存在未通过协调器的重叠文件锁", e);
            }
            fileLocked = true;
        } catch (IOException e) {
            throw new UncheckedIOException("获取跨实例文件锁失败", e);
        } finally {
            if (!fileLocked) {
                entry.fileLock = null;
                entry.monitor.unlock();
            }
        }
    }

    @Override
    public void unlock() {
        IOException releaseError = null;
        try {
            FileLock fileLock = entry.fileLock;
            if (fileLock != null) {
                fileLock.release();
            }
        } catch (IOException e) {
            releaseError = e;
        } finally {
            entry.fileLock = null;
            entry.monitor.unlock();
        }
        if (releaseError != null) {
            throw new UncheckedIOException("释放跨实例文件锁失败", releaseError);
        }
    }

    /** 同一锁文件在单 JVM 内共享的通道与监视器。 */
    private static final class SharedEntry {
        private final FileChannel channel;
        private final ReentrantLock monitor = new ReentrantLock();
        private FileLock fileLock;

        private SharedEntry(FileChannel channel) {
            this.channel = channel;
        }
    }
}
