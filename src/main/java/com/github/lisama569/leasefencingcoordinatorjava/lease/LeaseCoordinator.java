package com.github.lisama569.leasefencingcoordinatorjava.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Local coordinator for named lease locks.
 *
 * <p>Concurrency model: a single {@link ReentrantLock} serializes every
 * decision per coordinator instance, so concurrent acquire callers can
 * never observe the same pre-acquisition state and double-holding is
 * impossible. Time reads and expiry verdicts are derived from
 * {@link TimeSource}; persistence via {@link LeaseStore} makes the verdict
 * survive restarts.</p>
 */
@Component
public class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    /** Maximum accepted TTL: one day. */
    public static final long MAX_TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final LeaseStore store;
    private final TimeSource timeSource;
    private final ReentrantLock monitor = new ReentrantLock();

    public LeaseCoordinator(LeaseStore store, TimeSource timeSource) {
        this.store = store;
        this.timeSource = timeSource;
    }

    /**
     * Acquire a named lock.
     *
     * <ul>
     *   <li>unknown or expired lock &rarr; granted with a fresh, strictly
     *       greater fencing token;</li>
     *   <li>valid lease, same holder &rarr; the existing lease is returned
     *       unchanged (idempotent: holder, token and expiry never move);</li>
     *   <li>valid lease, other holder &rarr; {@link RejectReason#LOCK_HELD_BY_OTHER}.</li>
     * </ul>
     */
    public Lease acquire(String lockName, String holderId, long ttlMillis) {
        requireName(lockName);
        requireHolder(holderId);
        requireTtl(ttlMillis);
        monitor.lock();
        try {
            long now = timeSource.nowMillis();
            Lease existing = store.load(lockName).orElse(null);

            if (existing != null && !isExpired(existing, now)) {
                if (existing.holderId().equals(holderId)) {
                    log.info("acquire idempotent lock={} holder={} token={} now={} expiresAt={} basis=same_valid_holder",
                            lockName, holderId, existing.fencingToken(), now, existing.expiresAtMs());
                    return existing;
                }
                log.warn("acquire rejected lock={} caller={} currentHolder={} token={} now={} expiresAt={} basis={}",
                        lockName, holderId, existing.holderId(), existing.fencingToken(), now,
                        existing.expiresAtMs(), RejectReason.LOCK_HELD_BY_OTHER);
                throw new LeaseException(RejectReason.LOCK_HELD_BY_OTHER,
                        "lock '" + lockName + "' is currently held by '" + existing.holderId()
                                + "' until " + existing.expiresAtMs());
            }

            long nextToken = existing == null ? 1L : existing.fencingToken() + 1L;
            String takeover = existing == null ? "first_acquire" : "takeover_after_expiry";
            Lease granted = new Lease(lockName, holderId, nextToken, now, now + ttlMillis);
            store.save(granted);
            log.info("acquire granted lock={} holder={} token={} now={} expiresAt={} ttl={} basis={}",
                    lockName, holderId, nextToken, now, granted.expiresAtMs(), ttlMillis, takeover);
            return granted;
        } finally {
            monitor.unlock();
        }
    }

    /**
     * Renew a lease held by {@code holderId} under {@code fencingToken}.
     *
     * <p>The new expiry is {@code max(currentExpiry, now + ttl)}: renewal
     * can only extend, never shorten the remaining lease, and it never
     * changes holder or token.</p>
     */
    public Lease renew(String lockName, String holderId, long fencingToken, long ttlMillis) {
        requireName(lockName);
        requireHolder(holderId);
        requireTtl(ttlMillis);
        requireToken(fencingToken);
        monitor.lock();
        try {
            long now = timeSource.nowMillis();
            Lease existing = store.load(lockName).orElse(null);
            if (existing == null) {
                log.warn("renew rejected lock={} caller={} token={} now={} basis={}",
                        lockName, holderId, fencingToken, now, RejectReason.UNKNOWN_LOCK);
                throw new LeaseException(RejectReason.UNKNOWN_LOCK,
                        "no lease record exists for lock '" + lockName + "'");
            }
            if (!existing.holderId().equals(holderId)) {
                log.warn("renew rejected lock={} caller={} currentHolder={} token={} currentToken={} now={} basis={}",
                        lockName, holderId, existing.holderId(), fencingToken,
                        existing.fencingToken(), now, RejectReason.HOLDER_MISMATCH);
                throw new LeaseException(RejectReason.HOLDER_MISMATCH,
                        "lock '" + lockName + "' is held by '" + existing.holderId() + "'");
            }
            if (existing.fencingToken() != fencingToken) {
                log.warn("renew rejected lock={} holder={} token={} currentToken={} now={} basis={}",
                        lockName, holderId, fencingToken, existing.fencingToken(), now,
                        RejectReason.TOKEN_STALE);
                throw new LeaseException(RejectReason.TOKEN_STALE,
                        "token " + fencingToken + " does not match current token "
                                + existing.fencingToken() + " for lock '" + lockName + "'");
            }
            if (isExpired(existing, now)) {
                log.warn("renew rejected lock={} holder={} token={} now={} expiresAt={} basis={}",
                        lockName, holderId, fencingToken, now, existing.expiresAtMs(),
                        RejectReason.LEASE_EXPIRED);
                throw new LeaseException(RejectReason.LEASE_EXPIRED,
                        "lease for lock '" + lockName + "' expired at " + existing.expiresAtMs()
                                + "; acquire is required");
            }

            long candidateExpiry = now + ttlMillis;
            long newExpiry = Math.max(existing.expiresAtMs(), candidateExpiry);
            if (newExpiry == existing.expiresAtMs()) {
                log.info("renew noop lock={} holder={} token={} now={} expiresAt={} ttl={} basis=existing_expiry_later",
                        lockName, holderId, fencingToken, now, newExpiry, ttlMillis);
                return existing;
            }
            Lease renewed = new Lease(lockName, holderId, fencingToken, existing.acquiredAtMs(), newExpiry);
            store.save(renewed);
            log.info("renew granted lock={} holder={} token={} now={} oldExpiresAt={} newExpiresAt={} ttl={} basis=extend",
                    lockName, holderId, fencingToken, now, existing.expiresAtMs(), newExpiry, ttlMillis);
            return renewed;
        } finally {
            monitor.unlock();
        }
    }

    /**
     * Validate the fencing token carried by a protected operation.
     *
     * <p>Accepted only when the lock exists, the caller is the current
     * holder, the lease is unexpired and the presented token equals the
     * latest token. Older/superseded tokens, future unknown tokens and
     * expired leases are all rejected with a distinct reason.</p>
     */
    public FenceDecision validateFencedToken(String lockName, String holderId, long fencingToken) {
        requireName(lockName);
        requireHolder(holderId);
        requireToken(fencingToken);
        monitor.lock();
        try {
            long now = timeSource.nowMillis();
            Lease current = store.load(lockName).orElse(null);
            if (current == null) {
                return reject(RejectReason.UNKNOWN_LOCK,
                        "no lease record exists for lock '" + lockName + "'", null, lockName,
                        holderId, fencingToken, now);
            }
            if (isExpired(current, now)) {
                return reject(RejectReason.LEASE_EXPIRED,
                        "lease expired at " + current.expiresAtMs() + " (now=" + now + ")",
                        current, lockName, holderId, fencingToken, now);
            }
            // Token comparison precedes holder comparison on purpose: an older
            // token carried even by a former holder is first and foremost
            // superseded, which is the decisive fencing signal.
            if (fencingToken < current.fencingToken()) {
                return reject(RejectReason.TOKEN_SUPERSEDED,
                        "token " + fencingToken + " is older than current token "
                                + current.fencingToken(), current, lockName, holderId,
                        fencingToken, now);
            }
            if (fencingToken > current.fencingToken()) {
                return reject(RejectReason.TOKEN_STALE,
                        "token " + fencingToken + " was never granted for this lock "
                                + "(current token " + current.fencingToken() + ")",
                        current, lockName, holderId, fencingToken, now);
            }
            if (!current.holderId().equals(holderId)) {
                return reject(RejectReason.HOLDER_MISMATCH,
                        "caller '" + holderId + "' is not the current holder '"
                                + current.holderId() + "'", current, lockName, holderId,
                        fencingToken, now);
            }
            log.info("fence accepted lock={} holder={} token={} now={} expiresAt={} basis=token_current",
                    lockName, holderId, fencingToken, now, current.expiresAtMs());
            return new FenceDecision(true, null,
                    "token " + fencingToken + " is the current token and the lease is valid until "
                            + current.expiresAtMs(), current);
        } finally {
            monitor.unlock();
        }
    }

    /** A lease is expired at time {@code now} when {@code now >= expiresAtMs}. */
    private boolean isExpired(Lease lease, long now) {
        return now >= lease.expiresAtMs();
    }

    private FenceDecision reject(RejectReason reason, String detail, Lease current,
                                 String lockName, String holderId, long fencingToken, long now) {
        log.warn("fence rejected lock={} caller={} token={} currentToken={} now={} expiresAt={} basis={}",
                lockName, holderId, fencingToken,
                current == null ? null : current.fencingToken(), now,
                current == null ? null : current.expiresAtMs(), reason);
        return new FenceDecision(false, reason, detail, current);
    }

    private static void requireName(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER, "lockName must not be blank");
        }
    }

    private static void requireHolder(String holderId) {
        if (holderId == null || holderId.isBlank()) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER, "holderId must not be blank");
        }
    }

    private static void requireToken(long fencingToken) {
        if (fencingToken <= 0) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER,
                    "fencingToken must be positive but was " + fencingToken);
        }
    }

    private static void requireTtl(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER,
                    "ttlMillis must be positive but was " + ttlMillis);
        }
        if (ttlMillis > MAX_TTL_MILLIS) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER,
                    "ttlMillis " + ttlMillis + " exceeds maximum " + MAX_TTL_MILLIS);
        }
    }
}
