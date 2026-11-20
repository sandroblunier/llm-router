package ch.ace.llmrouter.api;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/**
 * OpenAPI metadata for /q/openapi and Swagger UI (/q/swagger-ui).
 * Declares the X-API-Key header so the "Authorize" button in Swagger UI works for all endpoints.
 */
@OpenAPIDefinition(
        info = @Info(title = "LLM Router", version = "0.1.0"),
        security = @SecurityRequirement(name = OpenApiDefinition.API_KEY_SCHEME))
@SecurityScheme(
        securitySchemeName = OpenApiDefinition.API_KEY_SCHEME,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        apiKeyName = ApiKeyFilter.HEADER)
public class OpenApiDefinition extends Application {

    static final String API_KEY_SCHEME = "ApiKey";
}
