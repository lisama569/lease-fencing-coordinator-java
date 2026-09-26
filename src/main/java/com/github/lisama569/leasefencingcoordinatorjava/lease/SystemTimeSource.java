package com.github.lisama569.leasefencingcoordinatorjava.lease;

import org.springframework.stereotype.Component;

/** Production time source backed by the wall clock. */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
