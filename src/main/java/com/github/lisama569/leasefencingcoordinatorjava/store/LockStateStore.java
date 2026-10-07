package com.github.lisama569.leasefencingcoordinatorjava.store;

import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;

import java.util.Map;

/**
 * 锁状态持久化抽象：保证重启后令牌不重发、租约判定一致，
 * 并为同机多实例共用同一状态目录提供跨进程互斥。
 */
public interface LockStateStore {

    /** 启动时载入全部锁状态；无持久化文件时返回空 Map。 */
    Map<String, LockLease> load();

    /** 原子地覆盖写入全部锁状态。 */
    void persist(Map<String, LockLease> locks);

    /**
     * 在“对这份状态全局唯一”的独占区（同 JVM 互斥 + 跨进程文件锁）内执行一次判定事务：
     * 进入临界区后先重新读取磁盘上的最新状态交给事务；事务返回的新状态（若有）在退出前落盘。
     *
     * <p>这样多个实例（无论是否同一进程）共用同一状态文件时，
     * “读到最新归属 → 判定 → 持久化结论”整体串行，授予对所有实例即时可见、结论唯一。
     */
    <T> T exclusively(LockTransaction<T> transaction);

    /** 一次独占判定事务：基于进入临界区时读到的最新快照计算，并选择只读或提交新状态。 */
    @FunctionalInterface
    interface LockTransaction<T> {
        StoreOutcome<T> execute(Map<String, LockLease> latest);
    }

    /** 事务返回载体：{@code committedState} 非 null 时，独占区退出前会原子落盘该状态。 */
    record StoreOutcome<T>(T value, Map<String, LockLease> committedState) {
        public static <T> StoreOutcome<T> readOnly(T value) {
            return new StoreOutcome<>(value, null);
        }

        public static <T> StoreOutcome<T> commit(T value, Map<String, LockLease> newState) {
            return new StoreOutcome<>(value, newState);
        }
    }
}
