package com.ffproject.flagconsumer.flag;

/**
 * Thrown when a flag is unknown or deleted. Mapped to {@code 404} by
 * {@code ApiExceptionHandler}.
 */
public class FlagNotFoundException extends RuntimeException {

    private final String tenantId;
    private final String flagName;

    public FlagNotFoundException(String tenantId, String flagName) {
        super("No flag named '" + flagName + "' for tenant '" + tenantId + "'");
        this.tenantId = tenantId;
        this.flagName = flagName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFlagName() {
        return flagName;
    }
}
