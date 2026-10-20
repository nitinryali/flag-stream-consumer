package com.ffproject.flagconsumer.web;

/**
 * Thrown when a request names no tenant and {@code consumer.tenant-id} is
 * unset. Mapped to {@code 400} by {@link ApiExceptionHandler}.
 */
public class MissingTenantException extends RuntimeException {

    public MissingTenantException() {
        super("Pass ?tenantId= or the X-Tenant-Id header.");
    }
}
