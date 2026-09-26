package com.github.lisama569.leasefencingcoordinatorjava.lease;

/**
 * Distinct, machine-readable rejection reasons for lease operations.
 */
public enum RejectReason {
    INVALID_PARAMETER,
    LOCK_HELD_BY_OTHER,
    ALREADY_HELD_BY_CALLER,
    UNKNOWN_LOCK,
    HOLDER_MISMATCH,
    TOKEN_STALE,
    TOKEN_SUPERSEDED,
    LEASE_EXPIRED,
    PERSISTENCE_FAILURE
}
