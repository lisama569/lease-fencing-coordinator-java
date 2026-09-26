package com.github.lisama569.leasefencingcoordinatorjava.lease;

/**
 * Outcome of validating a fencing token against a named lock.
 *
 * @param accepted  whether the protected operation may proceed
 * @param reason    rejection reason when not accepted, otherwise {@code null}
 * @param detail    human-readable explanation of the decision basis
 * @param current   current lease record (may be {@code null} if the lock is unknown)
 */
public record FenceDecision(boolean accepted,
                            RejectReason reason,
                            String detail,
                            Lease current) {
}
