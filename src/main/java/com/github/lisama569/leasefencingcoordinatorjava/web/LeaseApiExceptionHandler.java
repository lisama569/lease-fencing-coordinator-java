package com.github.lisama569.leasefencingcoordinatorjava.web;

import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseException;
import com.github.lisama569.leasefencingcoordinatorjava.lease.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link LeaseException} into distinguishable HTTP responses.
 *
 * <ul>
 *   <li>{@link RejectReason#INVALID_PARAMETER} &rarr; 400 Bad Request;</li>
 *   <li>{@link RejectReason#PERSISTENCE_FAILURE} &rarr; 500 Internal Server Error;</li>
 *   <li>all other rejections (contention, mismatch, stale token, expiry)
 *       &rarr; 409 Conflict, each with its own {@code reason} code.</li>
 * </ul>
 */
@RestControllerAdvice
public class LeaseApiExceptionHandler {

    /** Stable wire format for rejections: {@code reason} plus human message. */
    public record ErrorBody(String reason, String message) {
    }

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<ErrorBody> handleLease(LeaseException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case PERSISTENCE_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .body(new ErrorBody(ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(RejectReason.INVALID_PARAMETER.name(),
                        "malformed JSON request body"));
    }
}
