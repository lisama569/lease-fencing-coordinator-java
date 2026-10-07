package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 单把命名锁的可变持锁状态（协调器内部模型，同时作为持久化记录）。
 *
 * @param currentHolder    当前持有者标识；锁从未被持有时为 null
 * @param fencingToken     最近一次授予的栅栏令牌（锁级单调递增，也是该锁已发放的最大令牌）
 * @param acquiredAtMillis 当前持有者取得持有的绝对时间
 * @param expireAtMillis   当前持有者的到期绝对时间
 * @param releasedAtMillis 持有者主动让出的绝对时间；0 表示当前代次未被主动让出
 *                         （让出后锁处于“无有效持有者但已有发放历史”的状态，等待下一次授予）
 */
public record LockLease(
        String currentHolder,
        long fencingToken,
        long acquiredAtMillis,
        long expireAtMillis,
        long releasedAtMillis
) {
}
