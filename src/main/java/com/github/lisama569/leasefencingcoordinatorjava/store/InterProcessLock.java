package com.github.lisama569.leasefencingcoordinatorjava.store;

/**
 * 跨实例（跨 JVM）互斥锁：同一台机器上多个协调器实例共用同一份状态目录时，
 * 用它把“读取最新状态 → 判定 → 写回状态”串行化为一个跨进程临界区。
 */
public interface InterProcessLock {

    /** 获取跨进程排他锁（阻塞直到拿到）。 */
    void lock();

    /** 释放跨进程排他锁。 */
    void unlock();
}
