package com.github.lisama569.leasefencingcoordinatorjava.web;

import com.github.lisama569.leasefencingcoordinatorjava.lease.FenceDecision;
import com.github.lisama569.leasefencingcoordinatorjava.lease.Lease;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseException;
import com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints exposing acquire / renew / fence-check operations. */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseCoordinator coordinator;

    public LeaseController(LeaseCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/acquire")
    public Lease acquire(@RequestBody AcquireRequest request) {
        require(request != null);
        return coordinator.acquire(request.lockName(), request.holderId(),
                requireLong(request.ttlMillis(), "ttlMillis"));
    }

    @PostMapping("/renew")
    public Lease renew(@RequestBody RenewRequest request) {
        require(request != null);
        return coordinator.renew(request.lockName(), request.holderId(),
                requireLong(request.fencingToken(), "fencingToken"),
                requireLong(request.ttlMillis(), "ttlMillis"));
    }

    @PostMapping("/validate")
    public FenceDecision validate(@RequestBody ValidateRequest request) {
        require(request != null);
        return coordinator.validateFencedToken(request.lockName(), request.holderId(),
                requireLong(request.fencingToken(), "fencingToken"));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER, "request body is required");
        }
    }

    private static long requireLong(Long value, String field) {
        if (value == null) {
            throw new LeaseException(RejectReason.INVALID_PARAMETER, field + " is required");
        }
        return value;
    }
}
