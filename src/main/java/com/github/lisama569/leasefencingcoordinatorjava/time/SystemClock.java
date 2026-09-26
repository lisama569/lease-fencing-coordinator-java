package com.github.lisama569.leasefencingcoordinatorjava.time;

/** 基于系统墙钟的默认实现。 */
public class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
