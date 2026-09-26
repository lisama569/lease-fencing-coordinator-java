package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 一次成功的锁获取（含接管）结果。
 *
 * @param lockName          锁名
 * @param holderId          持有者标识
 * @param fencingToken      本次发放的严格递增栅栏令牌
 * @param acquiredAtMillis  本次取得持有的绝对时间（毫秒）
 * @param expireAtMillis    租约到期的绝对时间（毫秒）
 * @param ttlMillis         本次申请的 TTL
 * @param takeover          是否为接管一个已过期持有者的新授予
 * @param evaluatedAtMillis 协调器做出判定的时间（毫秒）
 */
public record LeaseGrant(
        String lockName,
        String holderId,
        long fencingToken,
        long acquiredAtMillis,
        long expireAtMillis,
        long ttlMillis,
        boolean takeover,
        long evaluatedAtMillis
) {
}
