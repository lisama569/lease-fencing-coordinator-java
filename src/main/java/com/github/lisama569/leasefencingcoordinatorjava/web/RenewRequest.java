package com.github.lisama569.leasefencingcoordinatorjava.web;

/** Request body for renewing a held lease. */
public record RenewRequest(String lockName, String holderId, Long fencingToken, Long ttlMillis) {
}
