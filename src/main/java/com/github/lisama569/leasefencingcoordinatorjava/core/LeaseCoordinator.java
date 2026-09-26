package com.github.lisama569.leasefencingcoordinatorjava.core;

import com.github.lisama569.leasefencingcoordinatorjava.model.AcquireResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.FenceCheckResult;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseGrant;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseView;
import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;
import com.github.lisama569.leasefencingcoordinatorjava.model.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.model.RenewResult;
import com.github.lisama569.leasefencingcoordinatorjava.store.LockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地租约锁协调器。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>并发下，同一把命名锁在任一时刻至多一个有效持有者（未到期持有者）；</li>
 *   <li>每次“新的授予”（首次获取 / 过期接管）都发放锁级严格递增的栅栏令牌，且令牌持久化、永不重发；</li>
 *   <li>续约不改变持有者与令牌，且不会缩短剩余租期；</li>
 *   <li>受保护操作只接受“当前令牌且租约未到期”这一种情况，其余全部以可区分原因拒绝。</li>
 * </ul>
 *
 * <p>所有判定在同一把内置互斥锁内、依据注入时钟一次性完成，因此结论确定、可复现。
 */
public class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    private final Clock clock;
    private final LockStateStore store;
    private final Object mutex = new Object();
    private final Map<String, LockLease> locks;

    public LeaseCoordinator(Clock clock, LockStateStore store) {
        this.clock = clock;
        this.store = store;
        this.locks = new LinkedHashMap<>(store.load());
    }

    // ------------------------------------------------------------------
    // 获取 / 接管
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

        synchronized (mutex) {
            LockLease existing = locks.get(lockName);

            if (existing == null) {
                // 全新锁：令牌从 1 开始
                long token = 1L;
                long expireAt = now + ttlMillis;
                LockLease next = new LockLease(holderId, token, now, expireAt);
                locks.put(lockName, next);
                store.persist(locks);
                LeaseGrant grant = grant(lockName, holderId, token, now, expireAt, ttlMillis, false, now);
                log.info("ACQUIRE grant lock={} holder={} token={} now={} expireAt={} ttl={} basis=lock_free",
                        lockName, holderId, token, now, expireAt, ttlMillis);
                return new AcquireResult(true, grant, null, "锁空闲，首次授予", now);
            }

            if (now < existing.expireAtMillis()) {
                // 存在有效（未到期）持有者
                if (existing.currentHolder().equals(holderId)) {
                    LeaseGrant grant = toGrant(lockName, existing, ttlMillis, false, now);
                    log.info("ACQUIRE reentrant lock={} holder={} token={} now={} expireAt={} basis=already_held_by_self",
                            lockName, holderId, existing.fencingToken(), now, existing.expireAtMillis());
                    return new AcquireResult(true, grant, null, "持有者仍是调用方，原样返回既有租约（令牌与到期时间不变）", now);
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
            LockLease next = new LockLease(holderId, newToken, now, expireAt);
            locks.put(lockName, next);
            store.persist(locks);
            LeaseGrant grant = grant(lockName, holderId, newToken, now, expireAt, ttlMillis, true, now);
            log.info("ACQUIRE takeover lock={} prevHolder={} newHolder={} prevToken={} newToken={} now={} expireAt={} basis=prev_lease_expired",
                    lockName, existing.currentHolder(), holderId, existing.fencingToken(), newToken, now, expireAt);
            return new AcquireResult(true, grant, null,
                    "旧持有者 " + existing.currentHolder() + " 的租约已到期，安全接管并发放新令牌", now);
        }
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

        synchronized (mutex) {
            LockLease existing = locks.get(lockName);

            if (existing == null) {
                log.info("RENEW reject lock={} caller={} now={} basis=lock_never_held",
                        lockName, holderId, now);
                return new RenewResult(false, null, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，无可续约租约", now);
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
                        existing.acquiredAtMillis(), newExpireAt);
                locks.put(lockName, current);
                store.persist(locks);
            }
            LeaseGrant grant = toGrant(lockName, current, ttlMillis, false, now);
            log.info("RENEW grant lock={} holder={} token={} now={} oldExpireAt={} newExpireAt={} shortened={} basis=holder_renew",
                    lockName, holderId, current.fencingToken(), now, existing.expireAtMillis(),
                    current.expireAtMillis(), changed);
            return new RenewResult(true, grant, null,
                    changed ? "续约成功，到期时间已向后顺延" : "续约成功，既有剩余租期更长，到期时间保持不变", now);
        }
    }

    // ------------------------------------------------------------------
    // 栅栏令牌保护
    // ------------------------------------------------------------------

    public FenceCheckResult validateProtectedOperation(String lockName, long fencingToken) {
        long now = clock.nowMillis();
        String nameError = validateLockName(lockName);
        if (nameError != null) {
            return fenceReject(lockName, fencingToken, 0L, RejectReason.INVALID_LOCK_NAME, nameError, now);
        }
        if (fencingToken <= 0L) {
            return fenceReject(lockName, fencingToken, 0L, RejectReason.INVALID_FENCING_TOKEN,
                    "栅栏令牌必须为正数，收到 " + fencingToken, now);
        }

        synchronized (mutex) {
            LockLease existing = locks.get(lockName);
            if (existing == null) {
                return fenceReject(lockName, fencingToken, 0L, RejectReason.LOCK_NOT_HELD,
                        "锁从未被获取，不存在任何已发放令牌", now);
            }
            long currentToken = existing.fencingToken();
            if (fencingToken > currentToken) {
                // 令牌单调稠密发放，比当前最新令牌更大意味着从未发放过
                return fenceReject(lockName, fencingToken, currentToken, RejectReason.UNKNOWN_FENCING_TOKEN,
                        "令牌从未对该锁发放过", now);
            }
            if (fencingToken < currentToken) {
                return fenceReject(lockName, fencingToken, currentToken, RejectReason.FENCING_TOKEN_SUPERSEDED,
                        "令牌已被更新的持有者令牌取代（更旧的一代）", now);
            }
            // fencingToken == currentToken
            if (now >= existing.expireAtMillis()) {
                return fenceReject(lockName, fencingToken, currentToken, RejectReason.LEASE_EXPIRED,
                        "当前持有者租约已到期，受保护操作必须被拒绝", now);
            }
            log.info("FENCE accept lock={} holder={} token={} now={} expireAt={} basis=current_token_and_lease_valid",
                    lockName, existing.currentHolder(), fencingToken, now, existing.expireAtMillis());
            return new FenceCheckResult(true, lockName, fencingToken, currentToken, null,
                    "令牌为当前持有者且租约未到期，允许执行", now);
        }
    }

    // ------------------------------------------------------------------
    // 只读视图
    // ------------------------------------------------------------------

    public LeaseView inspect(String lockName) {
        if (validateLockName(lockName) != null) {
            throw new IllegalArgumentException("锁名非法: " + lockName);
        }
        synchronized (mutex) {
            long now = clock.nowMillis();
            LockLease existing = locks.get(lockName);
            if (existing == null) {
                return new LeaseView(lockName, false, null, 0L, 0L, 0L, false, now);
            }
            boolean expired = now >= existing.expireAtMillis();
            return new LeaseView(lockName, !expired, existing.currentHolder(), existing.fencingToken(),
                    existing.acquiredAtMillis(), existing.expireAtMillis(), expired, now);
        }
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

    private FenceCheckResult fenceReject(String lockName, long presented, long current,
                                         RejectReason reason, String message, long now) {
        log.info("FENCE reject lock={} presentedToken={} currentToken={} now={} reason={} basis={}",
                lockName, presented, current, now, reason, reason.name());
        return new FenceCheckResult(false, lockName, presented, current, reason, message, now);
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
