package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 主动让出（release）的判定结果。released 为 true 时其余字段描述被让出的代次；
 * 否则 rejectReason 非空，且各种拒绝原因互相可区分。
 *
 * @param released          是否让出成功
 * @param lockName          锁名
 * @param holderId          请求让出的调用方（成功时即当前持有者本人）
 * @param releasedToken     被让出代次的栅栏令牌
 * @param releasedAtMillis  让出生效的绝对时间（毫秒）
 * @param rejectReason      拒绝原因
 * @param message           判定说明
 * @param evaluatedAtMillis 协调器做出判定的时间（毫秒）
 */
public record ReleaseResult(
        boolean released,
        String lockName,
        String holderId,
        long releasedToken,
        long releasedAtMillis,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
