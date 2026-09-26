package com.github.lisama569.leasefencingcoordinatorjava.lease;

import java.util.Optional;

/**
 * Persistent record of every named lock.
 *
 * <p>Implementations must be durable enough that a process restart never
 * re-issues a previously granted token: the last granted token of each lock
 * is preserved. Expired leases are retained too, so that restart gives the
 * same expiry verdict.</p>
 */
public interface LeaseStore {

    /** Load the persisted record of a lock, if one ever existed. */
    Optional<Lease> load(String lockName);

    /** Persist a lease record (insert or replace) and flush durably. */
    void save(Lease lease);
}
