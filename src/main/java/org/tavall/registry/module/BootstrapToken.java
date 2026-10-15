package org.tavall.registry.module;

import java.util.Objects;
import java.util.UUID;

/**
 * Unforgeable internal token authorizing framework-managed bootstrap and cascade operations.
 * <p>
 * <b>Security Invariant:</b> Instances can only be constructed by package-private framework coordinators.
 * External public callers cannot forge this token to bypass generation or actor fences.
 */
final class BootstrapToken {

    private final UUID tokenScope;

    BootstrapToken(UUID tokenScope) {
        this.tokenScope = Objects.requireNonNull(tokenScope, "tokenScope must not be null");
    }

    UUID tokenScope() {
        return tokenScope;
    }

    boolean matches(UUID epochId) {
        return tokenScope.equals(epochId);
    }
}
