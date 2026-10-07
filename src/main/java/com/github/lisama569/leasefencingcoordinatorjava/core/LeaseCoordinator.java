package com.github.lisama569.leasefencingcoordinatorjava.core;

import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseView;
import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.ReleaseResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.RenewResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.LockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本地租约锁协调器（支持同机多实例共用同一状态目录）。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>多个实例共用同一份状态时，任一时刻同一把命名锁至多一个有效持有者；
 *       某个实例刚授予的归属，其他实例下一次判定时必然读到，结论全局唯一；</li>
 *   <li>每次“新的授予”（首次获取 / 过期接管 / 让出后再获取）都发放锁级严格递增的栅栏令牌，
 *       令牌持久化，换实例、重启都不会重发；</li>
 *   <li>续约不改变持有者与令牌，且不会缩短剩余租期；</li>
 *   <li>受保护操作只接受“令牌 == 当前最新令牌 <b>且调用方就是该令牌持有者本人</b> 且租约未到期”，
 *       身份不符、过期、被取代、从未发放分别给出可区分原因；</li>
 *   <li>持有者可在到期前主动让出；让出立即对所有实例生效，让出后再获取发放更大令牌。</li>
 * </ul>
 *
 * <p>所有判定都通过 {@link LockStateStore#exclusively} 在“同 JVM 互斥 + 跨进程文件锁”的
 * 独占区内，基于进入临界区时重新读盘的最新状态、依据注入时钟一次性完成，因此结论确定、可复现。
 */
public class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    private final Clock clock;
    private final LockStateStore store;
    private final String instanceId;

    public LeaseCoordinator(Clock clock, LockStateStore store) {
        this.clock = clock;
        this.store = store;
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------
    // 获取 / 接管 / 让出后再获取
    // ------------------------------------------------------------------

    public AcquireResult acquire(String lockName, String holderId, long ttlMillis) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return acquireReject(RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        String holderError = validateHolderId(holderId);
        if (holderError != null) {
            return acquireReject(RejectReason.INVALID_HOLDER_ID, holderError, now);
        }
        String ttlError = validateTtl(ttlMillis, now);
        if (ttlError != null) {
            return acquireReject(RejectReason.INVALID_TTL, ttlError, now);
        }

        return store.exclusively(latest -> {
            LockLease existing = latest.get(lockName);

            if (existing == null) {
                // 全新锁：令牌从 1 开始
                long token = 1L;
                long expireAt = now + ttlMillis;
                LockLease next = new LockLease(holderId, token, now, expireAt, 0L);
                Map<String, LockLease> committed = commit(latest, lockName, next);
                LeaseGrant grant = grant(lockName, holderId, token, now, expireAt, ttlMillis, false, now);
                log.info("ACQUIRE grant instance={} lock={} holder={} token={} now={} expireAt={} ttl={} basis=lock_free",
                        instanceId, lockName, holderId, token, now, expireAt, ttlMillis);
                return LockStateStore.StoreOutcome.commit(
                        new AcquireResult(true, grant, null, "锁空闲，首次授予", now), committed);
            }

            boolean released = existing.releasedAtMillis() != 0L;
            boolean expired = now >= existing.expireAtMillis();
            if (!released && !expired) {
                // 存在有效（未到期、未让出）持有者
                if (existing.currentHolder().equals(holderId)) {
                    LeaseGrant grant = toGrant(lockName, existing, ttlMillis, false, now);
                    log.info("ACQUIRE reentrant instance={} lock={} holder={} token={} now={} expireAt={} basis=already_held_by_self",
                            instanceId, lockName, holderId, existing.fencingToken(), now, existing.expireAtMillis());
                    return LockStateStore.StoreOutcome.readOnly(new AcquireResult(true, grant, null,
                            "持有者仍是调用方，原样返回既有租约（令牌与到期时间不变）", now));
                }
                log.info("ACQUIRE reject instance={} lock={} caller={} holder={} token={} now={} expireAt={} basis=held_by_other",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return LockStateStore.StoreOutcome.readOnly(new AcquireResult(false, null, RejectReason.HELD_BY_OTHER,
                        "锁由其他有效持有者 " + existing.currentHolder() + " 持有，未到期", now));
            }

            // 锁已无有效持有者：过期接管 或 主动让出后再获取 -> 都发放更大的新令牌
            long newToken = existing.fencingToken() + 1L;
            long expireAt = now + ttlMillis;
            boolean takeover = expired && !released;
            LockLease next = new LockLease(holderId, newToken, now, expireAt, 0L);
            Map<String, LockLease> committed = commit(latest, lockName, next);
            LeaseGrant grant = grant(lockName, holderId, newToken, now, expireAt, ttlMillis, takeover, now);
            String basis = released ? "prev_lease_released" : "prev_lease_expired";
            log.info("ACQUIRE grant instance={} lock={} prevHolder={} newHolder={} prevToken={} newToken={} now={} expireAt={} takeover={} basis={}",
                    instanceId, lockName, existing.currentHolder(), holderId, existing.fencingToken(), newToken,
                    now, expireAt, takeover, basis);
            String message = released
                    ? "锁已被上一持有者 " + existing.currentHolder() + " 主动让出，立即授予并发放新令牌"
                    : "旧持有者 " + existing.currentHolder() + " 的租约已到期，安全接管并发放新令牌";
            return LockStateStore.StoreOutcome.commit(new AcquireResult(true, grant, null, message, now), committed);
        });
    }

    // ------------------------------------------------------------------
    // 续约
    // ------------------------------------------------------------------

    public RenewResult renew(String lockName, String holderId, long ttlMillis) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return renewReject(RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        String holderError = validateHolderId(holderId);
        if (holderError != null) {
            return renewReject(RejectReason.INVALID_HOLDER_ID, holderError, now);
        }
        String ttlError = validateTtl(ttlMillis, now);
        if (ttlError != null) {
            return renewReject(RejectReason.INVALID_TTL, ttlError, now);
        }

        return store.exclusively(latest -> {
            LockLease existing = latest.get(lockName);

            if (existing == null) {
                log.info("RENEW reject instance={} lock={} caller={} now={} basis=lock_never_held",
                        instanceId, lockName, holderId, now);
                return LockStateStore.StoreOutcome.readOnly(new RenewResult(false, null, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，无可续约租约", now));
            }
            if (existing.releasedAtMillis() != 0L) {
                log.info("RENEW reject instance={} lock={} caller={} holder={} token={} now={} releasedAt={} basis=lease_released",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.releasedAtMillis());
                return LockStateStore.StoreOutcome.readOnly(new RenewResult(false, null, RejectReason.LEASE_RELEASED,
                        "当前代次已被持有者主动让出，不能续约，必须重新获取并取得新令牌", now));
            }
            if (now >= existing.expireAtMillis()) {
                log.info("RENEW reject instance={} lock={} caller={} holder={} token={} now={} expireAt={} basis=lease_expired",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return LockStateStore.StoreOutcome.readOnly(new RenewResult(false, null, RejectReason.LEASE_EXPIRED,
                        "租约已到期，不能续约，必须重新获取（接管）并取得新令牌", now));
            }
            if (!existing.currentHolder().equals(holderId)) {
                log.info("RENEW reject instance={} lock={} caller={} holder={} token={} now={} expireAt={} basis=held_by_other",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return LockStateStore.StoreOutcome.readOnly(new RenewResult(false, null, RejectReason.HELD_BY_OTHER,
                        "锁由其他有效持有者 " + existing.currentHolder() + " 持有", now));
            }

            // 合法续约：持有者与令牌不变；到期时间只向后推，不缩短剩余租期
            long requestedExpireAt = now + ttlMillis;
            long newExpireAt = Math.max(existing.expireAtMillis(), requestedExpireAt);
            boolean changed = newExpireAt != existing.expireAtMillis();
            LockLease current = existing;
            if (changed) {
                current = new LockLease(existing.currentHolder(), existing.fencingToken(),
                        existing.acquiredAtMillis(), newExpireAt, existing.releasedAtMillis());
                LeaseGrant grant = toGrant(lockName, current, ttlMillis, false, now);
                log.info("RENEW grant instance={} lock={} holder={} token={} now={} oldExpireAt={} newExpireAt={} basis=holder_renew",
                        instanceId, lockName, holderId, current.fencingToken(), now, existing.expireAtMillis(),
                        current.expireAtMillis());
                return LockStateStore.StoreOutcome.commit(new RenewResult(true, grant, null,
                        "续约成功，到期时间已向后顺延", now), commit(latest, lockName, current));
            }
            LeaseGrant grant = toGrant(lockName, current, ttlMillis, false, now);
            log.info("RENEW grant instance={} lock={} holder={} token={} now={} expireAt={} shortened=false basis=holder_renew",
                    instanceId, lockName, holderId, current.fencingToken(), now, current.expireAtMillis());
            return LockStateStore.StoreOutcome.readOnly(new RenewResult(true, grant, null,
                    "续约成功，既有剩余租期更长，到期时间保持不变", now));
        });
    }

    // ------------------------------------------------------------------
    // 主动让出
    // ------------------------------------------------------------------

    /**
     * 当前持有者在到期前主动让出锁。让出立即对所有共用状态的实例生效：
     * 其他调用方可立刻获取，并拿到比被让出令牌更大的新令牌。
     *
     * <p>拒绝原因与其它场景互相可区分：
     * 锁从未获取 {@link RejectReason#LOCK_NOT_HELD}；已被让出 {@link RejectReason#LEASE_RELEASED}；
     * 已过期 {@link RejectReason#LEASE_EXPIRED}；不是本人 {@link RejectReason#NOT_CURRENT_HOLDER}。
     */
    public ReleaseResult release(String lockName, String holderId) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return releaseReject(lockName, holderId, 0L, RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        String holderError = validateHolderId(holderId);
        if (holderError != null) {
            return releaseReject(lockName, holderId, 0L, RejectReason.INVALID_HOLDER_ID, holderError, now);
        }

        return store.exclusively(latest -> {
            LockLease existing = latest.get(lockName);
            if (existing == null) {
                log.info("RELEASE reject instance={} lock={} caller={} now={} basis=lock_never_held",
                        instanceId, lockName, holderId, now);
                return LockStateStore.StoreOutcome.readOnly(releaseReject(lockName, holderId, 0L,
                        RejectReason.LOCK_NOT_HELD, "锁从未被获取，无可让出的持有", now));
            }
            if (existing.releasedAtMillis() != 0L) {
                log.info("RELEASE reject instance={} lock={} caller={} holder={} token={} now={} releasedAt={} basis=lease_released",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.releasedAtMillis());
                return LockStateStore.StoreOutcome.readOnly(releaseReject(lockName, holderId, existing.fencingToken(),
                        RejectReason.LEASE_RELEASED, "当前代次已被主动让出，锁不存在有效持有者", now));
            }
            if (now >= existing.expireAtMillis()) {
                log.info("RELEASE reject instance={} lock={} caller={} holder={} token={} now={} expireAt={} basis=lease_expired",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return LockStateStore.StoreOutcome.readOnly(releaseReject(lockName, holderId, existing.fencingToken(),
                        RejectReason.LEASE_EXPIRED, "租约已到期，不能再主动让出（到期即自动失效）", now));
            }
            if (!existing.currentHolder().equals(holderId)) {
                log.info("RELEASE reject instance={} lock={} caller={} holder={} token={} now={} expireAt={} basis=not_current_holder",
                        instanceId, lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return LockStateStore.StoreOutcome.readOnly(releaseReject(lockName, holderId, existing.fencingToken(),
                        RejectReason.NOT_CURRENT_HOLDER,
                        "当前有效持有者是 " + existing.currentHolder() + "，只有持有者本人可以主动让出", now));
            }

            // 合法让出：保留持有人代次与令牌（令牌永不复用），仅标记让出时刻 -> 锁立即空闲
            LockLease released = new LockLease(existing.currentHolder(), existing.fencingToken(),
                    existing.acquiredAtMillis(), existing.expireAtMillis(), now);
            Map<String, LockLease> committed = commit(latest, lockName, released);
            log.info("RELEASE grant instance={} lock={} holder={} token={} now={} expireAt={} releasedAt={} basis=holder_release",
                    instanceId, lockName, holderId, released.fencingToken(), now,
                    released.expireAtMillis(), now);
            return LockStateStore.StoreOutcome.commit(new ReleaseResult(true, lockName, holderId,
                    released.fencingToken(), now, null,
                    "持有者主动让出成功，锁立即对其他调用方开放，下一次授予发放更大令牌", now), committed);
        });
    }

    // ------------------------------------------------------------------
    // 栅栏令牌保护
    // ------------------------------------------------------------------

    /**
     * 兼容老用法：不校验调用方身份，只校验“令牌为当前最新令牌且租约未到期”。
     * 新用法请用 {@link #validateProtectedOperation(String, String, long)}。
     */
    public FenceCheckResult validateProtectedOperation(String lockName, long fencingToken) {
        return doValidateProtectedOperation(lockName, null, fencingToken, false);
    }

    /**
     * 带调用方身份的受保护操作校验。只接受一种情况：
     * {@code 令牌 == 当前最新令牌} 且 {@code 调用方 == 该令牌对应持有者本人} 且租约未到期、未让出。
     *
     * <p>身份不符（{@link RejectReason#HOLDER_MISMATCH}）与过期（{@link RejectReason#LEASE_EXPIRED}）、
     * 已让出（{@link RejectReason#LEASE_RELEASED}）、被新一代令牌取代
     * （{@link RejectReason#FENCING_TOKEN_SUPERSEDED}）、从未发放
     * （{@link RejectReason#UNKNOWN_FENCING_TOKEN}）分别给出可区分原因。
     */
    public FenceCheckResult validateProtectedOperation(String lockName, String holderId, long fencingToken) {
        return doValidateProtectedOperation(lockName, holderId, fencingToken, true);
    }

    private FenceCheckResult doValidateProtectedOperation(String lockName, String holderId,
                                                          long fencingToken, boolean checkIdentity) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return fenceReject(lockName, holderId, fencingToken, 0L,
                    RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        if (checkIdentity && validateHolderId(holderId) != null) {
            return fenceReject(lockName, holderId, fencingToken, 0L,
                    RejectReason.INVALID_HOLDER_ID, "持有者标识不能为 null 或空白", now);
        }
        if (fencingToken <= 0L) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_FENCING_TOKEN,
                    "栅栏令牌必须为正数，收到 " + fencingToken, now);
        }

        return store.exclusively(latest -> {
            LockLease existing = latest.get(lockName);
            if (existing == null) {
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken, 0L,
                        RejectReason.LOCK_NOT_HELD, "锁从未被获取，不存在任何已发放令牌", now));
            }
            long currentToken = existing.fencingToken();
            if (fencingToken > currentToken) {
                // 令牌单调稠密发放，比当前最新令牌更大意味着从未发放过
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken,
                        currentToken, RejectReason.UNKNOWN_FENCING_TOKEN, "令牌从未对该锁发放过", now));
            }
            if (fencingToken < currentToken) {
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken,
                        currentToken, RejectReason.FENCING_TOKEN_SUPERSEDED,
                        "令牌已被更新的持有者令牌取代（更旧的一代）", now));
            }
            // fencingToken == currentToken
            if (existing.releasedAtMillis() != 0L) {
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken,
                        currentToken, RejectReason.LEASE_RELEASED,
                        "该令牌对应代次已被持有者主动让出，受保护操作必须被拒绝", now));
            }
            if (checkIdentity && !existing.currentHolder().equals(holderId)) {
                // 令牌数字正确，但来的不是持有者本人：身份不匹配必须独立可区分
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken,
                        currentToken, RejectReason.HOLDER_MISMATCH,
                        "令牌属于持有者 " + existing.currentHolder() + "，调用方 " + holderId + " 不是持有者本人", now));
            }
            if (now >= existing.expireAtMillis()) {
                return LockStateStore.StoreOutcome.readOnly(fenceReject(lockName, holderId, fencingToken,
                        currentToken, RejectReason.LEASE_EXPIRED,
                        "当前持有者租约已到期，受保护操作必须被拒绝", now));
            }
            log.info("FENCE accept instance={} lock={} holder={} caller={} token={} now={} expireAt={} basis=current_token_identity_and_lease_valid",
                    instanceId, lockName, existing.currentHolder(), checkIdentity ? holderId : "(identity-not-checked)",
                    fencingToken, now, existing.expireAtMillis());
            return LockStateStore.StoreOutcome.readOnly(new FenceCheckResult(true, lockName, holderId,
                    fencingToken, currentToken, null, "令牌、持有者身份与租约有效期全部匹配，允许执行", now));
        });
    }

    // ------------------------------------------------------------------
    // 只读视图（同样进入独占区，读到的是所有实例持久化的最新归属）
    // ------------------------------------------------------------------

    public LeaseView inspect(String lockName) {
        if (validateLockName(lockName) != null) {
            throw new IllegalArgumentException("锁名非法: " + lockName);
        }
        long now = clock.nowMillis();
        return store.exclusively(latest -> {
            LockLease existing = latest.get(lockName);
            if (existing == null) {
                return LockStateStore.StoreOutcome.readOnly(
                        new LeaseView(lockName, false, null, 0L, 0L, 0L, false, false, now));
            }
            boolean expired = now >= existing.expireAtMillis();
            boolean released = existing.releasedAtMillis() != 0L;
            boolean held = !expired && !released;
            return LockStateStore.StoreOutcome.readOnly(new LeaseView(lockName, held, existing.currentHolder(),
                    existing.fencingToken(), existing.acquiredAtMillis(), existing.expireAtMillis(),
                    expired, released, now));
        });
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 以读入快照为基础生成一份新的可变 Map 并替换目标锁，供提交落盘使用。 */
    private static Map<String, LockLease> commit(Map<String, LockLease> latest, String lockName, LockLease next) {
        Map<String, LockLease> committed = new LinkedHashMap<>(latest);
        committed.put(lockName, next);
        return committed;
    }

    private static AcquireResult acquireReject(RejectReason reason, String message, long now) {
        return new AcquireResult(false, null, reason, message, now);
    }

    private static RenewResult renewReject(RejectReason reason, String message, long now) {
        return new RenewResult(false, null, reason, message, now);
    }

    private FenceCheckResult fenceReject(String lockName, String presentedHolder, long presented, long current,
                                         RejectReason reason, String message, long now) {
        log.info("FENCE reject instance={} lock={} caller={} presentedToken={} currentToken={} now={} reason={} basis={}",
                instanceId, lockName, presentedHolder, presented, current, now, reason, reason.name());
        return new FenceCheckResult(false, lockName, presentedHolder, presented, current, reason, message, now);
    }

    private static ReleaseResult releaseReject(String lockName, String holderId, long token,
                                               RejectReason reason, String message, long now) {
        return new ReleaseResult(false, lockName, holderId, token, 0L, reason, message, now);
    }

    private static LeaseGrant grant(String lockName, String holderId, long token, long acquiredAt,
                                    long expireAt, long ttl, boolean takeover, long now) {
        return new LeaseGrant(lockName, holderId, token, acquiredAt, expireAt, ttl, takeover, now);
    }

    private static LeaseGrant toGrant(String lockName, LockLease lease, long requestedTtl,
                                      boolean takeover, long now) {
        return new LeaseGrant(lockName, lease.currentHolder(), lease.fencingToken(),
                lease.acquiredAtMillis(), lease.expireAtMillis(), requestedTtl, takeover, now);
    }

    private static String validateLockName(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            return "锁名不能为 null 或空白";
        }
        return null;
    }

    private static String validateHolderId(String holderId) {
        if (holderId == null || holderId.isBlank()) {
            return "持有者标识不能为 null 或空白";
        }
        return null;
    }

    private static String validateTtl(long ttlMillis, long now) {
        if (ttlMillis <= 0L) {
            return "TTL 必须为正数毫秒，收到 " + ttlMillis;
        }
        if (ttlMillis > Long.MAX_VALUE - now) {
            return "TTL 过大，到期时间会溢出";
        }
        return null;
    }
}
