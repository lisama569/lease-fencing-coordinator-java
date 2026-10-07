package com.github.lisama569.leasefencingcoordinatorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地 JSON 文件的持久化实现。
 *
 * <p>写入采用“同目录临时文件 + 原子 rename”，崩溃只会留下旧完整文件或新完整文件，
 * 不会出现半截状态；读取据此在重启后给出与宕机前一致的令牌与到期判定。
 *
 * <p>多实例（同机多个进程，或同一进程内多个协调器）共用同一状态文件时，
 * {@link #exclusively(LockTransaction)} 用两层互斥保证“读最新状态 → 判定 → 落盘”整体串行：
 * <ol>
 *   <li>JVM 内：按状态文件绝对路径分配的全局 monitor（同一 JVM 对同一文件只能持有一个
 *       {@link FileLock}，否则抛 {@link OverlappingFileLockException}）；</li>
 *   <li>跨 JVM：状态文件旁的 {@code *.lock} 锁文件上的排他 {@link FileLock}。</li>
 * </ol>
 * 因此一个实例刚授予的结论，其他实例下一次进入临界区时必然能读到，不会出现互相矛盾的归属。
 */
public class FileLockStateStore implements LockStateStore {

    /** 每个状态文件路径一把 JVM 内 monitor，避免同 JVM 内重复文件锁并串行化本进程访问。 */
    private static final ConcurrentHashMap<Path, Object> JVM_MONITORS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final Object jvmMonitor;
    private final ObjectMapper objectMapper;

    public FileLockStateStore(Path file) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
        this.jvmMonitor = JVM_MONITORS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object());
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Map<String, LockLease> load() {
        synchronized (jvmMonitor) {
            return loadFromDisk();
        }
    }

    @Override
    public void persist(Map<String, LockLease> locks) {
        synchronized (jvmMonitor) {
            writeAtomically(locks);
        }
    }

    @Override
    public <T> T exclusively(LockTransaction<T> transaction) {
        synchronized (jvmMonitor) {
            ensureParentAndLockFile();
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                // 阻塞直到拿到跨 JVM 排他锁；tryLock 在同 JVM 竞争 monitor 时也兜底
                FileLock lock = channel.lock();
                try {
                    // 进入临界区后一定重新读盘：拿到的是其他实例刚持久化的最新归属与令牌
                    Map<String, LockLease> latest = loadFromDisk();
                    LockStateStore.StoreOutcome<T> outcome = transaction.execute(latest);
                    if (outcome.committedState() != null) {
                        writeAtomically(outcome.committedState());
                    }
                    return outcome.value();
                } finally {
                    lock.release();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("无法取得状态文件独占锁: " + lockFile, e);
            }
        }
    }

    // ------------------------------------------------------------------

    private Map<String, LockLease> loadFromDisk() {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) {
                return new LinkedHashMap<>();
            }
            Map<String, LockLease> loaded = objectMapper.readValue(
                    Files.readAllBytes(file),
                    objectMapper.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, LockLease.class));
            return new LinkedHashMap<>(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取锁状态文件: " + file, e);
        }
    }

    private void writeAtomically(Map<String, LockLease> locks) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, ".lease-state-", ".tmp");
            try {
                byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(locks);
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入锁状态文件: " + file, e);
        }
    }

    private void ensureParentAndLockFile() {
        try {
            Path parent = lockFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(lockFile)) {
                // 先创建空锁文件，供所有实例对同一个 inode 加锁
                Files.createFile(lockFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化锁文件: " + lockFile, e);
        }
    }
}
