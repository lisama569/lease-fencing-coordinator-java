package com.github.lisama569.leasefencingcoordinatorjava.core;

import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseView;
import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.ReleaseResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.RenewResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.InterProcessLock;
import com.github.lisama569.leasefencingcoordinatorjava.store.LockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 本地租约锁协调器。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>并发下，同一把命名锁在任一时刻至多一个有效持有者（未到期、未让出）；
 *       同一台机器上多个实例共用同一份状态目录时也成立——所有“读状态 → 判定 → 写状态”
 *       都在跨实例文件锁 + JVM 内互斥锁的双重临界区中一次性完成；</li>
 *   <li>每次“新的授予”（首次获取 / 过期接管 / 让出后重新获取）都发放锁级严格递增的栅栏令牌，
 *       且令牌持久化、跨实例共享、永不重发；</li>
 *   <li>续约不改变持有者与令牌，且不会缩短剩余租期；</li>
 *   <li>受保护操作只接受“当前令牌对应的持有者本人且租约未到期”这一种情况，其余全部以
 *       可区分原因拒绝；</li>
 *   <li>持有者可在到期前主动让出；让出立即对所有实例生效，下一次授予发放更大令牌。</li>
 * </ul>
 *
 * <p>所有判定依据注入时钟在临界区内一次性完成，因此结论确定、可复现。
 */
public class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    private final Clock clock;
    private final LockStateStore store;
    private final InterProcessLock crossInstanceLock;
    private final Object mutex = new Object();
    private final Map<String, LockLease> locks;

    /** 单实例用法（向后兼容）：不启用跨实例文件锁。 */
    public LeaseCoordinator(Clock clock, LockStateStore store) {
        this(clock, store, null);
    }

    /**
     * 多实例共用状态目录用法：传入跨实例互斥锁，每次判定都会先取跨实例锁并从磁盘重载状态，
     * 保证任一实例刚写入的归属、令牌与让出结论对其他实例立即可见。
     */
    public LeaseCoordinator(Clock clock, LockStateStore store, InterProcessLock crossInstanceLock) {
        this.clock = clock;
        this.store = store;
        this.crossInstanceLock = crossInstanceLock;
        this.locks = new LinkedHashMap<>(store.load());
    }

    // ------------------------------------------------------------------
    // 获取 / 接管 / 让出后重新获取
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

        return inSection(() -> {
            LockLease existing = locks.get(lockName);

            if (existing == null) {
                // 全新锁：令牌从 1 开始
                long token = 1L;
                long expireAt = now + ttlMillis;
                LockLease next = new LockLease(holderId, token, now, expireAt, false);
                locks.put(lockName, next);
                store.persist(locks);
                LeaseGrant grant = grant(lockName, holderId, token, now, expireAt, ttlMillis, false, now);
                log.info("ACQUIRE grant lock={} holder={} token={} now={} expireAt={} ttl={} basis=lock_free",
                        lockName, holderId, token, now, expireAt, ttlMillis);
                return new AcquireResult(true, grant, null, "锁空闲，首次授予", now);
            }

            if (existing.released()) {
                // 被前持有者主动让出：所有实例都应立即看到锁已空闲，新授予发放更大令牌
                long newToken = existing.fencingToken() + 1L;
                long expireAt = now + ttlMillis;
                LockLease next = new LockLease(holderId, newToken, now, expireAt, false);
                locks.put(lockName, next);
                store.persist(locks);
                LeaseGrant grant = grant(lockName, holderId, newToken, now, expireAt, ttlMillis, false, now);
                log.info("ACQUIRE grant-after-release lock={} prevHolder={} newHolder={} prevToken={} newToken={} now={} expireAt={} basis=lock_voluntarily_released",
                        lockName, existing.currentHolder(), holderId, existing.fencingToken(), newToken, now, expireAt);
                return new AcquireResult(true, grant, null,
                        "锁已被前持有者 " + existing.currentHolder() + " 主动让出，立即重新授予并发放更大令牌", now);
            }

            if (now < existing.expireAtMillis()) {
                // 存在有效（未到期）持有者
                if (existing.currentHolder().equals(holderId)) {
                    LeaseGrant grant = toGrant(lockName, existing, ttlMillis, false, now);
                    log.info("ACQUIRE reentrant lock={} holder={} token={} now={} expireAt={} basis=already_held_by_self",
                            lockName, holderId, existing.fencingToken(), now, existing.expireAtMillis());
                    return new AcquireResult(true, grant, null,
                            "持有者仍是调用方，原样返回既有租约（令牌与到期时间不变）", now);
                }
                log.info("ACQUIRE reject lock={} caller={} holder={} token={} now={} expireAt={} basis=held_by_other",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return new AcquireResult(false, null, RejectReason.HELD_BY_OTHER,
                        "锁由其他有效持有者 " + existing.currentHolder() + " 持有，未到期", now);
            }

            // now >= expireAt：旧持有者已过期，调用方安全接管 -> 发放更大的新令牌
            long newToken = existing.fencingToken() + 1L;
            long expireAt = now + ttlMillis;
            LockLease next = new LockLease(holderId, newToken, now, expireAt, false);
            locks.put(lockName, next);
            store.persist(locks);
            LeaseGrant grant = grant(lockName, holderId, newToken, now, expireAt, ttlMillis, true, now);
            log.info("ACQUIRE takeover lock={} prevHolder={} newHolder={} prevToken={} newToken={} now={} expireAt={} basis=prev_lease_expired",
                    lockName, existing.currentHolder(), holderId, existing.fencingToken(), newToken, now, expireAt);
            return new AcquireResult(true, grant, null,
                    "旧持有者 " + existing.currentHolder() + " 的租约已到期，安全接管并发放新令牌", now);
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

        return inSection(() -> {
            LockLease existing = locks.get(lockName);

            if (existing == null) {
                log.info("RENEW reject lock={} caller={} now={} basis=lock_never_held",
                        lockName, holderId, now);
                return new RenewResult(false, null, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，无可续约租约", now);
            }
            if (existing.released()) {
                log.info("RENEW reject lock={} caller={} prevHolder={} token={} now={} basis=lock_released",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now);
                return new RenewResult(false, null, RejectReason.LOCK_RELEASED,
                        "锁已被持有者主动让出，无有效租约，必须重新获取并取得新令牌", now);
            }
            if (now >= existing.expireAtMillis()) {
                log.info("RENEW reject lock={} caller={} holder={} token={} now={} expireAt={} basis=lease_expired",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return new RenewResult(false, null, RejectReason.LEASE_EXPIRED,
                        "租约已到期，不能续约，必须重新获取（接管）并取得新令牌", now);
            }
            if (!existing.currentHolder().equals(holderId)) {
                log.info("RENEW reject lock={} caller={} holder={} token={} now={} expireAt={} basis=held_by_other",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return new RenewResult(false, null, RejectReason.HELD_BY_OTHER,
                        "锁由其他有效持有者 " + existing.currentHolder() + " 持有", now);
            }

            // 合法续约：持有者与令牌不变；到期时间只向后推，不缩短剩余租期
            long requestedExpireAt = now + ttlMillis;
            long newExpireAt = Math.max(existing.expireAtMillis(), requestedExpireAt);
            boolean changed = newExpireAt != existing.expireAtMillis();
            LockLease current = existing;
            if (changed) {
                current = new LockLease(existing.currentHolder(), existing.fencingToken(),
                        existing.acquiredAtMillis(), newExpireAt, false);
                locks.put(lockName, current);
                store.persist(locks);
            }
            LeaseGrant grant = toGrant(lockName, current, ttlMillis, false, now);
            log.info("RENEW grant lock={} holder={} token={} now={} oldExpireAt={} newExpireAt={} shortened={} basis=holder_renew",
                    lockName, holderId, current.fencingToken(), now, existing.expireAtMillis(),
                    current.expireAtMillis(), changed);
            return new RenewResult(true, grant, null,
                    changed ? "续约成功，到期时间已向后顺延" : "续约成功，既有剩余租期更长，到期时间保持不变", now);
        });
    }

    // ------------------------------------------------------------------
    // 栅栏令牌保护（仅令牌：兼容单实例老用法，不校验调用方身份）
    // ------------------------------------------------------------------

    public FenceCheckResult validateProtectedOperation(String lockName, long fencingToken) {
        return doValidateProtectedOperation(lockName, null, fencingToken);
    }

    /**
     * 带调用方身份的受保护操作校验（多实例推荐用法）。只接受一种情况：
     * {@code 锁未让出} 且 {@code token == 当前最新令牌} 且 {@code holderId == 当前持有者本人}
     * 且 {@code 租约未到期}。其余全部以互相可区分的原因拒绝。
     */
    public FenceCheckResult validateProtectedOperation(String lockName, String holderId, long fencingToken) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        String holderError = validateHolderId(holderId);
        if (holderError != null) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_HOLDER_ID, holderError, now);
        }
        if (fencingToken <= 0L) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_FENCING_TOKEN,
                    "栅栏令牌必须为正数，收到 " + fencingToken, now);
        }
        return fenceInSection(lockName, holderId, fencingToken, now, true);
    }

    private FenceCheckResult doValidateProtectedOperation(String lockName, String holderId, long fencingToken) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        if (fencingToken <= 0L) {
            return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.INVALID_FENCING_TOKEN,
                    "栅栏令牌必须为正数，收到 " + fencingToken, now);
        }
        return fenceInSection(lockName, holderId, fencingToken, now, false);
    }

    private FenceCheckResult fenceInSection(String lockName, String holderId, long fencingToken,
                                            long now, boolean checkIdentity) {
        return inSection(() -> {
            LockLease existing = locks.get(lockName);
            if (existing == null) {
                return fenceReject(lockName, holderId, fencingToken, 0L, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，不存在任何已发放令牌", now);
            }
            long currentToken = existing.fencingToken();
            if (fencingToken > currentToken) {
                // 令牌单调稠密发放，比当前最新令牌更大意味着从未发放过
                return fenceReject(lockName, holderId, fencingToken, currentToken, RejectReason.UNKNOWN_FENCING_TOKEN,
                        "令牌从未对该锁发放过", now);
            }
            if (fencingToken < currentToken) {
                return fenceReject(lockName, holderId, fencingToken, currentToken,
                        RejectReason.FENCING_TOKEN_SUPERSEDED,
                        "令牌已被更新的持有者令牌取代（更旧的一代）", now);
            }
            // fencingToken == currentToken：先判租约是否已死（让出/过期），再判来人身份，
            // 与 renew 及仅令牌校验的判定顺序保持一致，也让“身份不符”专属于“锁仍有效、只是人不对”。
            if (existing.released()) {
                return fenceReject(lockName, holderId, fencingToken, currentToken, RejectReason.LOCK_RELEASED,
                        "锁已被持有者主动让出，旧一代令牌不能再用于受保护操作，须重新获取新令牌", now);
            }
            if (now >= existing.expireAtMillis()) {
                return fenceReject(lockName, holderId, fencingToken, currentToken, RejectReason.LEASE_EXPIRED,
                        "当前持有者租约已到期，受保护操作必须被拒绝", now);
            }
            if (checkIdentity && !existing.currentHolder().equals(holderId)) {
                // 租约仍有效、令牌数字也对，但来的人不是这枚令牌对应的持有者本人
                return fenceReject(lockName, holderId, fencingToken, currentToken,
                        RejectReason.HOLDER_IDENTITY_MISMATCH,
                        "令牌虽为当前令牌且租约未到期，但调用方 " + holderId + " 不是该令牌持有者 "
                                + existing.currentHolder() + " 本人", now);
            }
            log.info("FENCE accept lock={} caller={} holder={} token={} now={} expireAt={} basis={}",
                    lockName, holderId, existing.currentHolder(), fencingToken, now,
                    existing.expireAtMillis(),
                    checkIdentity ? "current_holder_and_token_and_lease_valid"
                            : "current_token_and_lease_valid");
            return new FenceCheckResult(true, lockName, holderId, fencingToken, currentToken, null,
                    checkIdentity ? "调用方为当前令牌持有者本人且租约未到期，允许执行"
                            : "令牌为当前持有者且租约未到期，允许执行", now);
        });
    }

    // ------------------------------------------------------------------
    // 主动让出
    // ------------------------------------------------------------------

    /**
     * 持有者在租约到期前主动让出锁。让出立即对所有共用状态的实例生效：锁立刻可被他人获取，
     * 下一次授予发放 {@code 旧令牌 + 1} 的新令牌。
     *
     * <p>拒绝原因：
     * <ul>
     *   <li>{@link RejectReason#LOCK_NOT_HELD} 锁从未被获取；</li>
     *   <li>{@link RejectReason#LOCK_RELEASED} 锁已被让出（调用方已不是当前持有者，并入 NOT_CURRENT_HOLDER）；</li>
     *   <li>{@link RejectReason#LEASE_EXPIRED} 租约已到期，持有者身份已随过期失效；</li>
     *   <li>{@link RejectReason#NOT_CURRENT_HOLDER} 未到期，但调用方不是当前持有者本人。</li>
     * </ul>
     */
    public ReleaseResult release(String lockName, String holderId) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return releaseReject(lockName, holderId, 0L, 0L, RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        String holderError = validateHolderId(holderId);
        if (holderError != null) {
            return releaseReject(lockName, holderId, 0L, 0L, RejectReason.INVALID_HOLDER_ID, holderError, now);
        }

        return inSection(() -> {
            LockLease existing = locks.get(lockName);
            if (existing == null) {
                log.info("RELEASE reject lock={} caller={} now={} basis=lock_never_held",
                        lockName, holderId, now);
                return releaseReject(lockName, holderId, 0L, 0L, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，无可让出的持有", now);
            }
            if (existing.released()) {
                log.info("RELEASE reject lock={} caller={} prevHolder={} token={} now={} basis=already_released",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now);
                return releaseReject(lockName, holderId, existing.fencingToken(), existing.expireAtMillis(),
                        RejectReason.NOT_CURRENT_HOLDER,
                        "锁已被主动让出，当前不存在有效持有者", now);
            }
            if (now >= existing.expireAtMillis()) {
                log.info("RELEASE reject lock={} caller={} holder={} token={} now={} expireAt={} basis=lease_expired",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return releaseReject(lockName, holderId, existing.fencingToken(), existing.expireAtMillis(),
                        RejectReason.LEASE_EXPIRED,
                        "租约已到期，持有者身份已失效，无需也不能主动让出", now);
            }
            if (!existing.currentHolder().equals(holderId)) {
                log.info("RELEASE reject lock={} caller={} holder={} token={} now={} expireAt={} basis=not_current_holder",
                        lockName, holderId, existing.currentHolder(), existing.fencingToken(), now,
                        existing.expireAtMillis());
                return releaseReject(lockName, holderId, existing.fencingToken(), existing.expireAtMillis(),
                        RejectReason.NOT_CURRENT_HOLDER,
                        "锁当前由其他有效持有者 " + existing.currentHolder() + " 持有，调用方无权让出", now);
            }

            // 合法让出：保留持有者与令牌留痕，置 released；状态持久化后对所有实例立即可见
            LockLease released = new LockLease(existing.currentHolder(), existing.fencingToken(),
                    existing.acquiredAtMillis(), existing.expireAtMillis(), true);
            locks.put(lockName, released);
            store.persist(locks);
            log.info("RELEASE accept lock={} holder={} releasedToken={} now={} expireAtWas={} basis=holder_voluntary_release",
                    lockName, holderId, existing.fencingToken(), now, existing.expireAtMillis());
            return new ReleaseResult(true, lockName, holderId, existing.fencingToken(),
                    existing.expireAtMillis(), null,
                    "持有者主动让出成功，锁立即空闲；下一次获取将发放 " + (existing.fencingToken() + 1L), now);
        });
    }

    // ------------------------------------------------------------------
    // 只读视图
    // ------------------------------------------------------------------

    public LeaseView inspect(String lockName) {
        if (validateLockName(lockName) != null) {
            throw new IllegalArgumentException("锁名非法: " + lockName);
        }
        long now = clock.nowMillis();
        return inSection(() -> {
            LockLease existing = locks.get(lockName);
            if (existing == null) {
                return new LeaseView(lockName, false, null, 0L, 0L, 0L, false, false, now);
            }
            boolean expired = now >= existing.expireAtMillis();
            boolean held = !expired && !existing.released();
            return new LeaseView(lockName, held, existing.currentHolder(), existing.fencingToken(),
                    existing.acquiredAtMillis(), existing.expireAtMillis(),
                    expired, existing.released(), now);
        });
    }

    // ------------------------------------------------------------------
    // 临界区：跨实例文件锁 + JVM 内互斥锁；进入时从磁盘重载最新状态
    // ------------------------------------------------------------------

    private <T> T inSection(Supplier<T> action) {
        if (crossInstanceLock == null) {
            synchronized (mutex) {
                return action.get();
            }
        }
        crossInstanceLock.lock();
        try {
            synchronized (mutex) {
                reload();
                return action.get();
            }
        } finally {
            crossInstanceLock.unlock();
        }
    }

    private void reload() {
        locks.clear();
        locks.putAll(store.load());
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private static AcquireResult acquireReject(RejectReason reason, String message, long now) {
        return new AcquireResult(false, null, reason, message, now);
    }

    private static RenewResult renewReject(RejectReason reason, String message, long now) {
        return new RenewResult(false, null, reason, message, now);
    }

    private FenceCheckResult fenceReject(String lockName, String presentedHolder, long presented, long current,
                                         RejectReason reason, String message, long now) {
        log.info("FENCE reject lock={} caller={} presentedToken={} currentToken={} now={} holder={} reason={} basis={}",
                lockName, presentedHolder, presented, current, now, holderOf(lockName), reason, reason.name());
        return new FenceCheckResult(false, lockName, presentedHolder, presented, current, reason, message, now);
    }

    private String holderOf(String lockName) {
        LockLease lease = locks.get(lockName);
        return lease == null ? null : lease.currentHolder();
    }

    private static ReleaseResult releaseReject(String lockName, String holderId, long token, long expireAt,
                                               RejectReason reason, String message, long now) {
        return new ReleaseResult(false, lockName, holderId, token, expireAt, reason, message, now);
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
