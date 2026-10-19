# Tavall Universal Module Lifecycle Architecture

## 1. Overview & Architectural Role

`tavall-registry` provides the canonical, reusable in-process module lifecycle registry and state coordinator for Tavall runtimes (e.g. Tavall Pay, Content Auto Post, Minecraft/Paper, and Web applications).

It formalizes the architecture model:
```
APPLICATION / RUNTIME -> SYSTEM -> FEATURE
```

### Core Responsibilities
- **Lifecycle State Machine**: Deterministic transitions across `DISCOVERED`, `LOADED`, `ENABLING`, `ENABLED`, `DISABLING`, `DISABLED`, `RELOADING`, `FAILED`, and `UNLOADED`.
- **Single Host DI Container Integration**: Integrates directly with the single host `IDependencyMap` using DI 1.1.0 `GenerationOwnedDependencyAccess`. There are **no** second containers or per-feature DI containers. Retained generation handles fail closed automatically upon unload or generation replacement.
- **Paper-Safe Host Serialization**: All lifecycle mutations are serialized through the registry monitor and validated against the designated host thread (`assertHostBoundary`). Asynchronous completion stages (quiescing, draining, starting) marshal completions back to the host boundary via `lifecycleExecutor`.
- **Reverse-Dependency Cascading with Desired State Preservation**: Disabling a parent `SYSTEM` cascades to child `FEATURE` modules in reverse topological order without destroying child declarative `desiredState`. Re-enabling the parent system re-enables only children with `desiredState == ENABLED`.
- **Residual Obligation Governance**: Disabling a payment or settlement provider rejects new transactions while preserving residual settlement, refund, recovery, and webhook capabilities via `ResidualObligationPolicy`.
- **Generation & Identity Fencing**: Blank actor rejection, optimistic generation fences (`lifecycleGeneration`, `configGeneration`), idempotency detection, and authorization callbacks (`IModuleLifecycleAuthorizer`). `force=true` never bypasses authorization or real required dependencies.

---

## 2. Module Taxonomy & Descriptors

### Primary Types (`ModulePrimaryType`)
- `RUNTIME`: Host environment adapter or engine root.
- `SYSTEM`: Foundational subsystem coordinating one or more features (e.g. `tavall-pay`, `auto-post-system`). Must NOT have a parent system.
- `FEATURE`: Specific operational capability (e.g. `pay-stripe`, `auto-post-creative`). Must declare a valid `parentSystemId` of type `SYSTEM` within the same runtime target.
- `APPLICATION`: Top-level application shell.
- `API`: Pure contract or specification export.
- `TOOLING`: Developer or diagnostic utility.
- `TEST_SUITE`: Test harness or simulation fixture.

### Secondary Roles (`ModuleSecondaryRole`)
- `LIBRARY`: Shared stateless or auxiliary logic.
- `PROVIDER`: Concrete service provider implementation.
- `ADAPTER`: Protocol or platform binding adapter.
- `INTEGRATION`: Cross-system integration bridge.

### Typed Identifiers
- `ModuleId`: Case-insensitive, hyphen-delimited module name (e.g. `ModuleId.of("pay-stripe")`).
- `RuntimeTarget`: Normalized target host identifier (e.g. `RuntimeTarget.of("web-commerce")`, `RuntimeTarget.APPLICATION`).
- `CapabilityId`: Namespaced operational capability (e.g. `CapabilityId.of("pay:new-initiation")`, `CapabilityId.of("pay:settlement")`).

---

## 3. Genuine Supported vs. Unsupported Semantics

To prevent false claims of capability:

| Capability | Default State | Semantics & Behavior |
| :--- | :--- | :--- |
| **Hot Reload (`RELOAD`)** | **`false` (Unsupported)** | Requires explicit `ModuleDescriptor.reloadSupported(true)` and proven participant eligibility. Attempting to reload an unsupported module returns a typed failure `ModuleFailure.of("reload_not_supported", ...)`. |
| **Rollback (`ROLLBACK`)** | **`false` (Unsupported)** | Requires explicit `ModuleDescriptor.rollbackSupported(true)`. Attempting to rollback an unsupported module returns a typed failure `ModuleFailure.of("rollback_not_supported", ...)`. |
| **Paper / JAR Classloader Isolation** | **External / Adapter-Owned** | In-process lifecycle manages DI generation binding, health, and state transitions. Custom classloader loading, bytecode transformations, or OSGi-style JAR unloading remain the explicit responsibility of the host adapter (e.g. `tavall-mc` Novus runtime). |
| **Durable Restart Persistence** | **Interface-First Contract** | `IModuleDesiredStateSnapshotProvider` defines snapshot persistence. `InMemoryDesiredStateSnapshotProvider` is strictly an in-memory test double (`isProductionPersistent() == false`) and must not be used for production persistence claims. |

---

## 4. Host Lifecycle Boundary & Concurrency (Paper-Safe)

Host environments like Paper/Minecraft require that all state-affecting calls run exclusively on the primary server thread.

### Concurrency Invariants
1. **Thread Serialization**: Every mutation checks `registry.assertHostBoundary()`. If the calling thread does not hold the registry monitor or fails `hostThreadPredicate`, an `IllegalStateException("Host serialization violation: ...")` is thrown immediately.
2. **Asynchronous Marshalling**: Participant stages (`onStartAsync`, `onQuiesceAsync`, `onDrainAsync`, `onCloseAsync`) return `CompletionStage<Void>`. When these stages complete (even on thread pools or netty worker threads), final transitions and DI unbindings are marshaled back to the host lifecycle boundary via `lifecycleExecutor.execute(...)`.
3. **Fail-Closed on Drain Failure**: If `onDrainAsync` completes exceptionally, the module transitions immediately to `FAILED`, its DI generation is closed and unbound, and the error is reported via `ModuleLifecycleResult`.

---

## 5. Dependency Injection & Generation Ownership

Integrates with Tavall DI 1.1.0 without introducing secondary or nested containers.

### Invariants
- **Generation-Owned Handles**: Services extending `GenerationOwnedDependencyAccess` track binding to the host `IDependencyMap`. When a module is reloaded or unloaded, the context unbinds the generation handle. Any retained reference invoking `requireDependencyGenerationBound()` fails closed immediately with an `IllegalStateException`.
- **Exact Token Ownership**: `ScopedModuleContext` tracks the exact instance bound for each token. When an old context closes, it only unbinds from `IDependencyMap` if the map still holds its exact instance. If a newer generation has already replaced the token or another module owns it, the host map is left untouched.
- **Collision & Borrowed Token Rejection**: A module cannot register a token already registered by another module, nor can it mutate or replace a token it did not register.

---

## 6. Desired State vs. Observed State

The lifecycle cleanly distinguishes between:
- **`ModuleDesiredState`**: Operator or deployment configuration intent (`ENABLED` or `DISABLED`). Persisted in configuration snapshots.
- **`ModuleLifecycleState`**: Actual observed runtime execution status (`DISCOVERED`, `LOADED`, `ENABLING`, `ENABLED`, `DISABLING`, `DISABLED`, `RELOADING`, `FAILED`, `UNLOADED`).

### System Cascade Behavior
When parent `SYSTEM` is disabled:
1. Child `FEATURE` modules are disabled in reverse topological dependency order.
2. The child's **observed** state becomes `DISABLED`, and its resources are quiesced and drained.
3. The child's **desired** state is **NOT** overwritten (it remains `ENABLED` if previously enabled).

When parent `SYSTEM` is subsequently re-enabled:
1. Only child `FEATURE` modules whose `desiredState` is `ENABLED` are re-enabled.
2. Child features that were independently disabled by an operator prior to system disable remain `DISABLED`.

---

## 7. Residual Obligation Governance (Tavall Pay)

Modules declaring capabilities can configure a `ResidualObligationPolicy`:

```java
public class StripeParticipant implements IModuleLifecycleParticipant {
    @Override
    public ResidualObligationPolicy residualObligationPolicy() {
        return ResidualObligationPolicy.of(
            CapabilityId.of("pay:webhook"),
            CapabilityId.of("pay:settlement"),
            CapabilityId.of("pay:refund"),
            CapabilityId.of("pay:recovery")
        );
    }
}
```

- When the module is `ENABLED`: All capabilities (including `pay:new-initiation`) are available.
- When the module is `DISABLED`: `registry.isCapabilityAvailable(newInitiation)` returns `false`. However, `registry.isCapabilityAvailable(...)` returns `true` for residual capabilities declared in `residualObligationPolicy`.
- Even forced disable requests (`force=true`) cannot bypass residual payment obligations.

---

## Outcome and Fencing Guarantees (2026-10-15 review corrections)

These behaviors are covered by `ModuleLifecycleRegistryIntegrationTest` and are part of the contract:

| Guarantee | Behavior |
| :--- | :--- |
| **Honest SYSTEM results** | Enabling a `SYSTEM` cascades to children whose desired state is `ENABLED`; if any child fails, the SYSTEM result is the typed failure `child_cascade_enable_failed` naming each failed child. Disable already fails with `child_cascade_drain_failed`. |
| **SYSTEM cascade failure inspection & health** | When a `SYSTEM`'s enable cascade reports `child_cascade_enable_failed`, the failure is recorded on the SYSTEM entry (`lastFailure`) so inspection and health reflect it, while keeping the SYSTEM's own lifecycle state truthful (`ENABLED`, since it did start). Aggregate health reports the failed children. |
| **Asynchronous participant load** | Initial load (`doLoad`, `loadAsync`, or implicit load during `enableAsync`) invokes `participant.onLoadAsync(context)` marshalled back to the host boundary before transitioning to `LOADED`. A failing load transitions to `FAILED` with `module_load_failed`, closes the context, and reports a typed failure. Reload invokes `onLoadAsync` for the new generation without double-calling. |
| **Reverse-dependency teardown order** | Shutdown (`closeAsync`) disables and unloads modules in reverse topological dependency order (dependents before their required dependencies, children before their parent `SYSTEM`), rather than reverse registration order. |
| **Dependency removal fails closed** | A public `DISABLE` is refused with `required_by_active_dependents` while an enabled module outside the SYSTEM's own children declares the target as a required dependency. A public `UNLOAD` is refused with `required_by_loaded_dependents` while a loaded module still requires it. |
| **Unload never skips drain** | Unloading an active module first runs quiesce and drain, then `onCloseAsync`, then releases the generation's DI bindings and resources. A failing close is reported as `module_close_failed`, never as `UNLOADED`. |
| **Rollback reports the real transition** | The rollback result reflects the disable/enable that realizes the restored desired state; a failed transition is a failed rollback. |
| **Desired-state authority is not truncated** | Every desired-state CAS starts from the restored snapshot, so a module that is not registered in the current runtime generation keeps its persisted desired state. |
| **Reservations belong to their request** | An in-flight reservation is keyed to the correlation that took it. Internal sub-operations on the same module cannot release it, and an exceptionally completed operation always releases its own reservation. |

