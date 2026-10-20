package com.ffproject.flagconsumer.flag;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.web.MissingTenantException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only flag API. This is a service-internal sidecar, so there is no auth
 * here; callers authenticate with the service that fronts it.
 *
 * <p>The tenant is resolved from {@code ?tenantId=}, then the
 * {@code X-Tenant-Id} header, then the configured {@code consumer.tenant-id}.</p>
 */
@RestController
@RequestMapping("/v1/flags")
public class FlagController {

    private final FlagStore store;
    private final ConsumerProperties properties;

    public FlagController(FlagStore store, ConsumerProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping
    public FlagsResponse list(
            @RequestParam(required = false) String tenantId,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader) {
        String tenant = resolveTenant(tenantId, tenantHeader);
        List<FlagView> flags = store.list(tenant);
        return new FlagsResponse(tenant, flags.size(), flags);
    }

    @GetMapping("/{name}")
    public FlagView get(
            @PathVariable String name,
            @RequestParam(required = false) String tenantId,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantHeader) {
        String tenant = resolveTenant(tenantId, tenantHeader);
        return store.get(tenant, name)
                .orElseThrow(() -> new FlagNotFoundException(tenant, name));
    }

    private String resolveTenant(String tenantId, String tenantHeader) {
        if (hasText(tenantId)) {
            return tenantId;
        }
        if (hasText(tenantHeader)) {
            return tenantHeader;
        }
        if (hasText(properties.tenantId())) {
            return properties.tenantId();
        }
        throw new MissingTenantException();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Response body of {@code GET /v1/flags}. */
    public record FlagsResponse(String tenantId, int count, List<FlagView> flags) {
    }
}
