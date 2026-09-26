package com.github.lisama569.leasefencingcoordinatorjava.tests.support;

import com.github.lisama569.leasefencingcoordinatorjava.time.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试可控时钟：时间只在测试显式推进时变化，无需任何真实等待。
 */
public class ManualClock implements Clock {

    private final AtomicLong now;

    public ManualClock() {
        this(1_000_000L);
    }

    public ManualClock(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    /** 将时间向前推进给定毫秒（不可回退）。 */
    public void advance(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("时间不可回退: " + deltaMillis);
        }
        now.addAndGet(deltaMillis);
    }

    /** 直接设置到某个绝对时间（用于越过到期点）。 */
    public void setTime(long millis) {
        if (millis < now.get()) {
            throw new IllegalArgumentException("时间不可回退: " + millis + " < " + now.get());
        }
        now.set(millis);
    }
}
