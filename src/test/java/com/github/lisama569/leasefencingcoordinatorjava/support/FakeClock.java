package com.github.lisama569.leasefencingcoordinatorjava.support;

import com.github.lisama569.leasefencingcoordinatorjava.lease.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/** Controllable clock for tests: time advances only when the test says so. */
public class FakeClock implements TimeSource {

    private final AtomicLong nowMs = new AtomicLong(1_000_000L);

    @Override
    public long nowMillis() {
        return nowMs.get();
    }

    public void setTime(long epochMillis) {
        nowMs.set(epochMillis);
    }

    public void advance(long deltaMillis) {
        nowMs.addAndGet(deltaMillis);
    }
}
