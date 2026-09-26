package com.github.lisama569.leasefencingcoordinatorjava.web;

/** Request body for validating a fencing token before a protected operation. */
public record ValidateRequest(String lockName, String holderId, Long fencingToken) {
}
