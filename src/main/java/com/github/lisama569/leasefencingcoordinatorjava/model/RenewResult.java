package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 续约的判定结果。renewed 为 true 时 grant 非空；否则 rejectReason 非空。
 */
public record RenewResult(
        boolean renewed,
        LeaseGrant grant,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
