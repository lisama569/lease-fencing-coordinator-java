package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 受栅栏令牌保护操作的校验结果。
 */
public record FenceCheckResult(
        boolean accepted,
        String lockName,
        long presentedToken,
        long currentToken,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
