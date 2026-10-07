package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 某把锁在某一时刻的只读快照，用于日志与测试断言。
 *
 * @param released 当前代次是否已被持有者主动让出（让出后 held=false，但已有发放历史）
 */
public record LeaseView(
        String lockName,
        boolean held,
        String holderId,
        long fencingToken,
        long acquiredAtMillis,
        long expireAtMillis,
        boolean expired,
        boolean released,
        long evaluatedAtMillis
) {
}
