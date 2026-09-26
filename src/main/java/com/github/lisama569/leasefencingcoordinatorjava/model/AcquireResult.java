package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 获取锁的判定结果。granted 为 true 时 grant 非空；否则 rejectReason 非空。
 */
public record AcquireResult(
        boolean granted,
        LeaseGrant grant,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
