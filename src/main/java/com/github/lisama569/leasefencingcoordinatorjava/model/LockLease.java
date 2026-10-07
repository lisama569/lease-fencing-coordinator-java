package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 单把命名锁的可变持锁状态（协调器内部模型，同时作为持久化记录）。
 *
 * @param currentHolder    当前持有者标识；锁从未被持有时为 null
 * @param fencingToken     最近一次授予（或让出）代际的栅栏令牌（锁级单调递增，永不重发）
 * @param acquiredAtMillis 当前持有者取得持有的绝对时间；主动让出后保留让出前的取得时间
 * @param expireAtMillis   当前持有者的到期绝对时间；主动让出后保留让出前的到期时间（已失效，仅留痕）
 * @param released         是否处于“被持有者主动让出、等待下一次授予”的状态
 */
public record LockLease(
        String currentHolder,
        long fencingToken,
        long acquiredAtMillis,
        long expireAtMillis,
        boolean released
) {

    /** 有效持有（未主动让出）状态的便捷构造器。 */
    public LockLease(String currentHolder, long fencingToken, long acquiredAtMillis, long expireAtMillis) {
        this(currentHolder, fencingToken, acquiredAtMillis, expireAtMillis, false);
    }
}
