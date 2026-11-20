package ch.ace.llmrouter.api;

import ch.ace.llmrouter.api.Dtos.ErrorResponse;
import ch.ace.llmrouter.config.RouterConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/** Checks the X-API-Key header on all REST endpoints (health endpoints under /q are not affected). */
@ApplicationScoped
public class ApiKeyFilter {

    static final String HEADER = "X-API-Key";

    @Inject
    RouterConfig config;

    void checkConfig(@Observes StartupEvent ev) {
        if (config.apiKey().enabled() && config.apiKey().value().filter(v -> !v.isBlank()).isEmpty()) {
            throw new IllegalStateException("router.api-key.enabled=true but router.api-key.value (ROUTER_API_KEY_VALUE) is not set");
        }
    }

    @ServerRequestFilter
    public Optional<Response> filter(ContainerRequestContext ctx) {
        if (!config.apiKey().enabled()) {
            return Optional.empty();
        }
        String expected = config.apiKey().value().orElse("");
        String given = ctx.getHeaderString(HEADER);
        if (given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(null, "missing or invalid " + HEADER))
                .build());
    }
}
