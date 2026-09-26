package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 某把锁在某一时刻的只读快照，用于日志与测试断言。
 */
public record LeaseView(
        String lockName,
        boolean held,
        String holderId,
        long fencingToken,
        long acquiredAtMillis,
        long expireAtMillis,
        boolean expired,
        long evaluatedAtMillis
) {
}
