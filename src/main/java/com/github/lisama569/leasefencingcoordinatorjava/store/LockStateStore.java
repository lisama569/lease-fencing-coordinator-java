package com.github.lisama569.leasefencingcoordinatorjava.store;

import com.github.lisama569.leasefencingcoordinatorjava.model.LockLease;

import java.util.Map;

/**
 * 锁状态持久化抽象：保证重启后令牌不重发、租约判定一致。
 */
public interface LockStateStore {

    /** 启动时载入全部锁状态；无持久化文件时返回空 Map。 */
    Map<String, LockLease> load();

    /** 原子地覆盖写入全部锁状态。 */
    void persist(Map<String, LockLease> locks);
}
