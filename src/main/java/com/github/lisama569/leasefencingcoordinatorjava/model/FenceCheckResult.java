package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 受栅栏令牌保护操作的校验结果。
 *
 * @param accepted         是否放行
 * @param lockName         锁名
 * @param presentedHolder  发起校验的调用方标识（不带身份校验的老用法为 null）
 * @param presentedToken   调用方出示的栅栏令牌
 * @param currentToken     该锁当前最新一代令牌
 * @param rejectReason     拒绝原因（accepted=true 时为 null）
 * @param message          判定说明
 * @param evaluatedAtMillis 协调器做出判定的时间（毫秒）
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
