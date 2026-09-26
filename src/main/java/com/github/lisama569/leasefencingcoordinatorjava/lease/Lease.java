package com.github.lisama569.leasefencingcoordinatorjava.lease;

/**
 * Snapshot of one named lock's current lease record.
 *
 * @param lockName       lock name
 * @param holderId       current holder identifier
 * @param fencingToken   monotonically increasing token granted on acquisition
 * @param acquiredAtMs   acquisition timestamp (epoch ms)
 * @param expiresAtMs    absolute expiry timestamp (epoch ms)
 */
public record Lease(String lockName,
                    String holderId,
                    long fencingToken,
                    long acquiredAtMs,
                    long expiresAtMs) {
}
