package com.eneve.agent.security;

import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

/**
 * Injects an OAuth2 Authorization Code + PKCE security scheme into the OpenAPI document
 * so that Swagger UI shows a Keycloak "Authorize" button.
 *
 * The Keycloak URLs are derived from the already-configured
 * {@code quarkus.oidc.auth-server-url} property to avoid duplication.
 *
 * Registered via {@code mp.openapi.filter} in application.properties.
 */
public class OpenApiSecurityFilter implements OASFilter {

    static final String SCHEME_NAME = "Keycloak";

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        String authServerUrl = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.oidc.auth-server-url", String.class)
                .orElse("https://sso-stage.julesenergy.com/auth/realms/code-agent");

        String authUrl  = authServerUrl + "/protocol/openid-connect/auth";
        String tokenUrl = authServerUrl + "/protocol/openid-connect/token";

        Map<String, String> scopes = Map.of(
                "openid",  "OpenID Connect",
                "profile", "User profile",
                "email",   "User email");

        OAuthFlow authCodeFlow = OASFactory.createOAuthFlow()
                .authorizationUrl(authUrl)
                .tokenUrl(tokenUrl)
                .refreshUrl(tokenUrl)
                .scopes(scopes);

        OAuthFlows flows = OASFactory.createOAuthFlows()
                .authorizationCode(authCodeFlow);

        SecurityScheme scheme = OASFactory.createSecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .flows(flows);

        if (openAPI.getComponents() == null) {
            openAPI.setComponents(OASFactory.createComponents());
        }
        openAPI.getComponents().addSecurityScheme(SCHEME_NAME, scheme);

        SecurityRequirement requirement = OASFactory.createSecurityRequirement()
                .addScheme(SCHEME_NAME);
        openAPI.addSecurityRequirement(requirement);
    }
}
