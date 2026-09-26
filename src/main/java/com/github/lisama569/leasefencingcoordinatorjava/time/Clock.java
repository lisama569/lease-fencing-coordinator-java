package com.github.lisama569.leasefencingcoordinatorjava.time;

/**
 * 可注入的时钟，便于测试在不真实等待的情况下推进时间。
 */
public interface Clock {

    /** 返回当前绝对时间（epoch 毫秒）。 */
    long nowMillis();
}
