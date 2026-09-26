package com.github.lisama569.leasefencingcoordinatorjava.web;

/** Request body for acquiring a named lock. */
public record AcquireRequest(String lockName, String holderId, Long ttlMillis) {
}
