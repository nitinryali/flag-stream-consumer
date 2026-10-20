package com.ffproject.flagconsumer.snapshot;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.flag.FlagSnapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The only two calls this consumer makes to the Flag Service: log in with the
 * service account, then read that tenant's flags.
 *
 * <p>RestClient is built by hand rather than injected so the timeouts are
 * explicit — a stuck snapshot request must not pin the scheduler thread.</p>
 */
@Component
public class FlagServiceClient {

    private final ConsumerProperties properties;
    private final RestClient restClient;

    public FlagServiceClient(ConsumerProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(10_000);

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (properties.flagServiceUrl() != null) {
            builder.baseUrl(properties.flagServiceUrl());
        }
        this.restClient = builder.build();
    }

    /** @return a fresh JWT for the service account */
    public String login() {
        LoginResponse response = restClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(properties.username(), properties.password()))
                .retrieve()
                .body(LoginResponse.class);

        if (response == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalStateException("login response did not contain a token");
        }
        return response.token();
    }

    /** @return the tenant's current flags */
    public List<FlagSnapshot> fetchFlags(String token) {
        FlagSnapshot[] flags = restClient.get()
                .uri("/flags")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Tenant-Id", properties.tenantId())
                .retrieve()
                .body(FlagSnapshot[].class);

        return flags == null ? List.of() : List.of(flags);
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token) {
    }
}
