package com.github.lisama569.leasefencingcoordinatorjava.config;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileChannelInterProcessLock;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.store.InterProcessLock;
import com.github.lisama569.leasefencingcoordinatorjava.store.LockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.Clock;
import com.github.lisama569.leasefencingcoordinatorjava.time.SystemClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 本地租约协调器的 Spring 装配。
 *
 * <p>状态文件路径通过 {@code lease.state.file} 配置，默认 {@code lease-state.json}。
 * 多实例共用同一状态文件时，会自动在其同目录启用一个跨实例文件锁（{@code <状态文件名>.lock}）。
 */
@Configuration
public class LeaseCoordinatorConfig {

    @Bean
    public Clock clock() {
        return new SystemClock();
    }

    @Bean
    public LockStateStore lockStateStore(
            @Value("${lease.state.file:lease-state.json}") String stateFile) {
        return new FileLockStateStore(Path.of(stateFile));
    }

    @Bean
    public InterProcessLock interProcessLock(
            @Value("${lease.state.file:lease-state.json}") String stateFile) {
        return new FileChannelInterProcessLock(Path.of(stateFile + ".lock"));
    }

    @Bean
    public LeaseCoordinator leaseCoordinator(Clock clock, LockStateStore store, InterProcessLock interProcessLock) {
        return new LeaseCoordinator(clock, store, interProcessLock);
    }
}
