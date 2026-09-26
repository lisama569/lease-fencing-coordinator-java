package com.github.lisama569.leasefencingcoordinatorjava.lease;

/** Business rejection carrying a distinguishable {@link RejectReason}. */
public class LeaseException extends RuntimeException {

    private final RejectReason reason;

    public LeaseException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
