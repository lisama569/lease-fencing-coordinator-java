package com.github.lisama569.leasefencingcoordinatorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于本地 JSON 文件的持久化实现。
 *
 * <p>写入采用“同目录临时文件 + 原子 rename”，崩溃只会留下旧完整文件或新完整文件，
 * 不会出现半截状态；读取据此在重启后给出与宕机前一致的令牌与到期判定。
 */
public class FileLockStateStore implements LockStateStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileLockStateStore(Path file) {
        this.file = file;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public synchronized Map<String, LockLease> load() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            if (Files.size(file) == 0) {
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

    @Override
    public synchronized void persist(Map<String, LockLease> locks) {
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
}
