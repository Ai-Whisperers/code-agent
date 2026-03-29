package com.eneve.agent.planner;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Request-scoped helper that resolves the current authenticated user identity
 * and checks plan ownership / admin access.
 */
@RequestScoped
public class PlanAuthHelper {

    @Inject SecurityIdentity securityIdentity;
    @Inject JsonWebToken jwt;

    public String resolveUserId() {
        if (securityIdentity.isAnonymous()) return "anonymous";
        try {
            String sub = jwt.getClaim("sub");
            if (sub != null && !sub.isBlank()) return sub;
        } catch (Exception ignored) { }
        return securityIdentity.getPrincipal().getName();
    }

    /** Returns a human-readable display name for the current user, preferring preferred_username over sub. */
    public String resolveDisplayName() {
        if (securityIdentity.isAnonymous()) return "anonymous";
        try {
            String preferred = jwt.getClaim("preferred_username");
            if (preferred != null && !preferred.isBlank()) return preferred;
            String sub = jwt.getClaim("sub");
            if (sub != null && !sub.isBlank()) return sub;
        } catch (Exception ignored) { }
        return securityIdentity.getPrincipal().getName();
    }

    /** Returns true when the current user is the plan's creator OR has the app_admin role. */
    public boolean isCreatorOrAdmin(ExecutionPlan plan) {
        if (securityIdentity.hasRole("app_admin")) return true;
        return resolveDisplayName().equals(plan.createdBy());
    }
}
