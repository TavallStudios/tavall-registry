package org.tavall.registry.module;

/**
 * Closed canonical enumeration of secondary responsibility roles in Tavall architecture.
 * <p>
 * <b>Architectural Role:</b> Captures secondary responsibilities such as provider implementation,
 * platform adaptation, or library reuse, orthogonal to the primary {@link ModulePrimaryType}.
 */
public enum ModuleSecondaryRole {

    /**
     * Reusable implementation capability consumed in-process; does not identify an
     * independently deployed runtime.
     */
    LIBRARY,

    /**
     * Pluggable concrete capability implementation selected by the owning SYSTEM or runtime.
     */
    PROVIDER,

    /**
     * Translation between APIs, protocols, platforms, or representations without establishing
     * a competing domain authority.
     */
    ADAPTER,

    /**
     * External product, third-party service, or protocol boundary with integration-specific contracts.
     */
    INTEGRATION
}
