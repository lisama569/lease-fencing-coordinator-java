package com.github.lisama569.leasefencingcoordinatorjava.model;

/**
 * 租约操作被拒绝时的可区分原因。
 */
public enum RejectReason {

    /** 锁名为 null 或空白。 */
    INVALID_LOCK_NAME,

    /** 持有者标识为 null 或空白。 */
    INVALID_HOLDER_ID,

    /** TTL 非法（非正数或溢出）。 */
    INVALID_TTL,

    /** 锁当前由其他有效持有者占有。 */
    HELD_BY_OTHER,

    /** 锁从未被获取，不存在可续约或可校验的租约。 */
    LOCK_NOT_HELD,

    /** 租约已到期，原持有者不能再续约或继续操作。 */
    LEASE_EXPIRED,

    /** 栅栏令牌格式非法（null 语义或非正数）。 */
    INVALID_FENCING_TOKEN,

    /** 栅栏令牌从未对该锁发放过。 */
    UNKNOWN_FENCING_TOKEN,

    /** 栅栏令牌已被更新的令牌取代（更旧的一代）。 */
    FENCING_TOKEN_SUPERSEDED,

    /** 受保护操作的调用方不是当前令牌对应的持有者本人（令牌数字对，但人不对）。 */
    HOLDER_IDENTITY_MISMATCH,

    /** 锁已被当前持有者主动让出（release），不存在有效持有者，须重新获取领取新令牌。 */
    LOCK_RELEASED,

    /** 主动让出时调用方不是当前有效持有者（锁在他人名下或已被让出）。 */
    NOT_CURRENT_HOLDER
}
