package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 受栅栏令牌保护操作的校验结果。
 *
 * @param presentedHolder  调用方自报的身份（holderId）；仅校验令牌的兼容用法时为 null
 */
public record FenceCheckResult(
        boolean accepted,
        String lockName,
        String presentedHolder,
        long presentedToken,
        long currentToken,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
