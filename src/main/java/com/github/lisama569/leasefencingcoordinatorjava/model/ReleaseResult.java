package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 主动让出（release）的判定结果。released 为 true 时表示让出成功，其余字段给出让出时刻的快照；
 * released 为 false 时 rejectReason 非空，且与其他操作的拒绝原因互相可区分。
 *
 * @param released          是否让出成功
 * @param lockName          锁名
 * @param holderId          发起让出的调用方
 * @param releasedToken     被让出的那一代令牌（成功时）
 * @param expireAtMillis    被让出前的到期绝对时间（成功时）
 * @param rejectReason      被拒绝的可区分原因（失败时）
 * @param message           判定说明
 * @param evaluatedAtMillis 协调器做出判定的时间（毫秒）
 */
public record ReleaseResult(
        boolean released,
        String lockName,
        String holderId,
        long releasedToken,
        long expireAtMillis,
        RejectReason rejectReason,
        String message,
        long evaluatedAtMillis
) {
}
