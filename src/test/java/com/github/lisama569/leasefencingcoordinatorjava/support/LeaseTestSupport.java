package com.github.lisama569.leasefencingcoordinatorjava.support;

import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseException;
import com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason;
import com.github.lisama569.leasefencingcoordinatorjava.lease.store.FileLeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared helpers: coordinator factory and reason assertions with logging. */
public final class LeaseTestSupport {

    private static final Logger log = LoggerFactory.getLogger(LeaseTestSupport.class);

    private LeaseTestSupport() {
    }

    public static LeaseCoordinator newCoordinator(Path storeDir, FakeClock clock) {
        return new LeaseCoordinator(new FileLeaseStore(storeDir.toString()), clock);
    }

    public static void assertReject(RejectReason expected, RunnableThrowing operation,
                                    String scenario) {
        try {
            operation.run();
        } catch (LeaseException ex) {
            log.info("[{}] rejected reason={} message={}", scenario, ex.getReason(), ex.getMessage());
            assertEquals(expected, ex.getReason(),
                    "scenario '" + scenario + "': expected " + expected + " but got "
                            + ex.getReason());
            return;
        }
        throw new AssertionError("scenario '" + scenario
                + "': expected rejection " + expected + " but operation succeeded");
    }

    @FunctionalInterface
    public interface RunnableThrowing {
        void run();
    }
}
