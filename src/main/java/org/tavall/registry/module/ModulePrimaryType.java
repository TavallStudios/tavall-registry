package org.tavall.registry.module;

/**
 * Closed canonical enumeration of primary module types defined in Tavall architecture policy.
 * <p>
 * <b>Architectural Role:</b> Classifies the primary structural responsibility of a module
 * according to MOD-01 and MOD-02 specifications. Governs lifecycle boundaries, fan-in composition,
 * and dependency validation.
 * <p>
 * <b>Invariants:</b> Every registered Tavall module possesses exactly one primary module type.
 */
public enum ModulePrimaryType {

    /**
     * Independently executable runtime or long-lived process that owns its own lifecycle
     * and may be independently deployed.
     */
    RUNTIME,

    /**
     * Consumer-facing fan-in and lifecycle boundary that composes related independently
     * controllable {@link #FEATURE} modules and/or existing capabilities through the owning
     * runtime's DI and registry. Does not imply a new OS process.
     */
    SYSTEM,

    /**
     * Bounded behavior with its own declared enable, disable, load, and reload lifecycle
     * under a parent {@link #SYSTEM}, where independence is justified by operational or
     * dependency boundaries. Does not imply a separate runtime.
     */
    FEATURE,

    /**
     * Application composition and entry module that assembles capabilities into an executable
     * application or bounded application surface.
     */
    APPLICATION,

    /**
     * Caller-facing contracts, request/result/data types, and stable capability boundaries
     * without owning the main runtime implementation.
     */
    API,

    /**
     * Build, development, administration, migration, generation, or operator tooling that
     * is not the product runtime itself.
     */
    TOOLING,

    /**
     * Test-only or validation-focused module that verifies one or more production modules
     * and does not ship as a production runtime.
     */
    TEST_SUITE
}
