package org.tavall.registry.module.operator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tavall.dependency.maps.DependencyMap;
import org.tavall.registry.module.CapabilityId;
import org.tavall.registry.module.HostLifecycleBoundary;
import org.tavall.registry.module.IRollbackCompatibilityValidator;
import org.tavall.registry.module.InMemoryDesiredStateSnapshotProvider;
import org.tavall.registry.module.ModuleDescriptor;
import org.tavall.registry.module.ModuleId;
import org.tavall.registry.module.ModuleLifecycleOperation;
import org.tavall.registry.module.ModuleLifecycleRegistry;
import org.tavall.registry.module.ModulePrimaryType;
import org.tavall.registry.module.ModuleSecondaryRole;
import org.tavall.registry.module.RuntimeTarget;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operator service over a real lifecycle registry: views, impact previews, confirmation of destructive
 * operations, generation fences, scope and the transport SPI.
 */
final class ModuleOperatorServiceIntegrationTest {

    private static final RuntimeTarget TARGET = RuntimeTarget.of("operator-test");
    private static final String OPERATOR = "operator@test";
    private static final ModuleId SYSTEM = ModuleId.of("demo-system");
    private static final ModuleId ALPHA = ModuleId.of("demo-alpha");
    private static final ModuleId BETA = ModuleId.of("demo-beta");
    private static final ModuleId HIDDEN = ModuleId.of("other-system");

    private ModuleLifecycleRegistry registry;
    private ModuleOperatorService service;

    @BeforeEach
    void start() throws Exception {
        registry = new ModuleLifecycleRegistry(TARGET, new DependencyMap(),
                new HostLifecycleBoundary(Runnable::run, thread -> true),
                request -> CompletableFuture.completedFuture(request.isBootstrap() || OPERATOR.equals(request.actor())),
                new InMemoryDesiredStateSnapshotProvider(),
                (target, generation, candidate, descriptors) -> CompletableFuture.completedFuture(
                        IRollbackCompatibilityValidator.ValidationResult.incompatible("no rollback catalog")));
        registry.whenReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        registry.registerModule(ModuleDescriptor.builder(SYSTEM, ModulePrimaryType.SYSTEM, TARGET).build(), null);
        registry.registerModule(ModuleDescriptor.builder(ALPHA, ModulePrimaryType.FEATURE, TARGET).parentSystemId(SYSTEM)
                .addSecondaryRole(ModuleSecondaryRole.PROVIDER).addProvidedCapability(CapabilityId.of("demo:alpha"))
                .reloadSupported(true).build(), null);
        registry.registerModule(ModuleDescriptor.builder(BETA, ModulePrimaryType.FEATURE, TARGET).parentSystemId(SYSTEM)
                .addRequiredDependency(ALPHA).addProvidedCapability(CapabilityId.of("demo:beta")).build(), null);
        registry.registerModule(ModuleDescriptor.builder(HIDDEN, ModulePrimaryType.SYSTEM, TARGET).build(), null);
        registry.reconcileDesiredStatesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        service = new ModuleOperatorService(registry, id -> id.value().startsWith("demo-"));
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void listsOnlyScopedModulesWithRelationshipsAndCapabilities() {
        List<ModuleOperatorView> views = service.list();

        assertEquals(List.of("demo-system", "demo-alpha", "demo-beta"), views.stream().map(ModuleOperatorView::moduleId).toList());
        ModuleOperatorView system = views.get(0);
        assertEquals(List.of("demo-alpha", "demo-beta"), system.children());
        ModuleOperatorView alpha = service.inspect("demo-alpha").orElseThrow();
        assertEquals("FEATURE", alpha.primaryType());
        assertEquals(List.of("PROVIDER"), alpha.secondaryRoles());
        assertEquals(List.of("demo-beta"), alpha.dependents());
        assertEquals(Map.of("demo:alpha", true), alpha.capabilities());
        assertTrue(service.inspect("other-system").isEmpty(), "modules outside the scope are invisible");
        assertThrows(IllegalArgumentException.class, () -> service.impact("other-system", ModuleLifecycleOperation.DISABLE));
    }

    @Test
    void destructiveOperationsRequireTheCurrentConfirmationToken() {
        ModuleOperatorView beta = service.inspect("demo-beta").orElseThrow();

        ModuleOperationOutcome unconfirmed = service.execute(OPERATOR, request(beta, ModuleLifecycleOperation.DISABLE, null));
        assertFalse(unconfirmed.succeeded());
        assertEquals("confirmation_required", unconfirmed.failureCode());
        assertEquals("ENABLED", service.inspect("demo-beta").orElseThrow().state());

        String token = unconfirmed.impact().confirmationToken();
        ModuleOperationOutcome confirmed = service.execute(OPERATOR, request(beta, ModuleLifecycleOperation.DISABLE, token));
        assertTrue(confirmed.succeeded());
        assertEquals("DISABLED", confirmed.state());

        // The token was bound to the previous configuration generation and cannot be replayed.
        ModuleOperatorView after = service.inspect("demo-beta").orElseThrow();
        ModuleOperationOutcome replay = service.execute(OPERATOR, request(after, ModuleLifecycleOperation.UNLOAD, token));
        assertEquals("confirmation_required", replay.failureCode());
    }

    @Test
    void systemImpactListsCascadedFeaturesAndEnableNeedsNoConfirmation() {
        ModuleOperationImpact impact = service.impact("demo-system", ModuleLifecycleOperation.DISABLE);
        assertTrue(impact.destructive());
        assertEquals(List.of("demo-system", "demo-alpha", "demo-beta"), impact.affectedModules());

        ModuleOperatorView system = service.inspect("demo-system").orElseThrow();
        assertTrue(service.execute(OPERATOR, request(system, ModuleLifecycleOperation.DISABLE, impact.confirmationToken())).succeeded());
        assertFalse(service.inspect("demo-alpha").orElseThrow().capabilities().get("demo:alpha"));

        ModuleOperationImpact enable = service.impact("demo-system", ModuleLifecycleOperation.ENABLE);
        assertFalse(enable.destructive());
        assertNull(enable.confirmationToken());
        ModuleOperatorView disabled = service.inspect("demo-system").orElseThrow();
        assertTrue(service.execute(OPERATOR, request(disabled, ModuleLifecycleOperation.ENABLE, null)).succeeded());
        assertTrue(service.inspect("demo-beta").orElseThrow().capabilities().get("demo:beta"));
    }

    @Test
    void registryRulesStillApplyThroughTheOperatorService() {
        ModuleOperatorView alpha = service.inspect("demo-alpha").orElseThrow();
        String token = service.impact("demo-alpha", ModuleLifecycleOperation.DISABLE).confirmationToken();

        assertEquals("required_by_active_dependents",
                service.execute(OPERATOR, request(alpha, ModuleLifecycleOperation.DISABLE, token)).failureCode());
        assertEquals("unauthorized",
                service.execute("viewer@test", request(alpha, ModuleLifecycleOperation.RELOAD,
                        service.impact("demo-alpha", ModuleLifecycleOperation.RELOAD).confirmationToken())).failureCode());
        ModuleOperationRequest stale = new ModuleOperationRequest("demo-alpha", ModuleLifecycleOperation.RELOAD,
                alpha.configGeneration() + 3, alpha.lifecycleGeneration(),
                service.impact("demo-alpha", ModuleLifecycleOperation.RELOAD).confirmationToken());
        assertEquals("stale_config_generation", service.execute(OPERATOR, stale).failureCode());

        ModuleOperationOutcome reload = service.execute(OPERATOR, request(alpha, ModuleLifecycleOperation.RELOAD,
                service.impact("demo-alpha", ModuleLifecycleOperation.RELOAD).confirmationToken()));
        assertTrue(reload.succeeded());
        assertTrue(reload.lifecycleGeneration() > alpha.lifecycleGeneration());
    }

    @Test
    void localTransportAndProviderDiscoveryServeTheSameTypedApi() {
        ModuleRuntimeTarget target = new ModuleRuntimeTarget("demo", "local-test", Map.of());
        try (IModuleOperatorTransport transport = new LocalModuleOperatorTransport(target, service, OPERATOR)) {
            assertEquals(3, transport.list().size());
            ModuleOperatorView beta = transport.inspect("demo-beta").orElseThrow();
            String token = transport.impact("demo-beta", ModuleLifecycleOperation.RELOAD).confirmationToken();
            assertEquals("reload_not_supported", transport.execute(request(beta, ModuleLifecycleOperation.RELOAD, token)).failureCode());
        }
        boolean discovered = ServiceLoader.load(IModuleOperatorTransportProvider.class).stream()
                .anyMatch(provider -> "local-test".equals(provider.get().transportType()));
        assertTrue(discovered);
    }

    private static ModuleOperationRequest request(ModuleOperatorView view, ModuleLifecycleOperation operation, String confirmation) {
        return new ModuleOperationRequest(view.moduleId(), operation, view.configGeneration(), view.lifecycleGeneration(), confirmation);
    }
}
