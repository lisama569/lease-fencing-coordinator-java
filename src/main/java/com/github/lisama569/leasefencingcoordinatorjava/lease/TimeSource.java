package com.github.lisama569.leasefencingcoordinatorjava.lease;

/**
 * Time abstraction so that lease expiry can be tested without real waiting.
 */
public interface TimeSource {
    /** Current epoch milliseconds. */
    long nowMillis();
}
