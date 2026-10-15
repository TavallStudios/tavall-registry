package org.tavall.registry.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tavall.dependency.DependencyAccess;
import org.tavall.dependency.GenerationOwnedDependencyAccess;
import org.tavall.dependency.annotations.DelegatesTo;
import org.tavall.dependency.maps.DependencyMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Universal Module Lifecycle Registry Integration Tests")
final class ModuleLifecycleRegistryIntegrationTest {

    private static final RuntimeTarget TARGET = RuntimeTarget.of("test-runtime");
    private DependencyMap hostDependencyMap;
    private InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore sharedSnapshotStore;
    private InMemoryDesiredStateSnapshotProvider snapshotProvider;
    private ModuleLifecycleRegistry registry;

    @BeforeEach
    void setUp() {
        hostDependencyMap = new DependencyMap();
        sharedSnapshotStore = new InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore();
        snapshotProvider = new InMemoryDesiredStateSnapshotProvider(sharedSnapshotStore);
        registry = createTestRegistry(Runnable::run);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null && registry.isConfigured()) {
            registry.close();
        }
    }

    private ModuleLifecycleRegistry createTestRegistry(Executor executor) {
        return createTestRegistry(TARGET, executor, snapshotProvider, LifecycleTestPolicies.allowAll(), LifecycleTestPolicies.acceptAllRollbacks());
    }

    private ModuleLifecycleRegistry createTestRegistry(
            RuntimeTarget target,
            Executor executor,
            IModuleDesiredStateSnapshotProvider provider,
            IModuleLifecycleAuthorizer authorizer,
            IRollbackCompatibilityValidator rollbackValidator
    ) {
        HostLifecycleBoundary boundary = new HostLifecycleBoundary(executor, thread -> true);
        return new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                target,
                hostDependencyMap,
                boundary,
                authorizer,
                provider,
                rollbackValidator
        );
    }

    private ModuleLifecycleRequest boot(ModuleLifecycleRegistry reg, String actor, ModuleId id, ModuleLifecycleOperation op) {
        BootstrapToken token = new BootstrapToken(reg.runtimeEpochId());
        return ModuleLifecycleRequest.internalBootstrap(token, reg.runtimeEpochId(), actor, id, op);
    }

    private ModuleLifecycleRequest boot(ModuleId id, ModuleLifecycleOperation op) {
        return boot(registry, "tester", id, op);
    }

    private ModuleLifecycleRequest req(ModuleId id, ModuleLifecycleOperation op, long configGen, long lifecycleGen) {
        return ModuleLifecycleRequest.request(registry.runtimeEpochId(), "tester", id, op, configGen, lifecycleGen);
    }

    private ModuleLifecycleRequest forcedReq(ModuleId id, ModuleLifecycleOperation op, long configGen, long lifecycleGen, boolean force) {
        return ModuleLifecycleRequest.forced(registry.runtimeEpochId(), "tester", id, op, configGen, lifecycleGen, force);
    }

    private static final class OwnerThreadTestGateway implements Executor {
        private final Thread owner;
        private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

        private OwnerThreadTestGateway(Thread owner) {
            this.owner = owner;
        }

        @Override
        public void execute(Runnable callback) {
            if (Thread.currentThread() == owner) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
        }

        private void pumpPending() {
            assertSame(owner, Thread.currentThread());
            Runnable cb;
            while ((cb = callbacks.poll()) != null) {
                cb.run();
            }
        }

        private <T> T await(CompletionStage<T> operation) throws Exception {
            assertSame(owner, Thread.currentThread());
            CompletableFuture<T> result = operation.toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                Runnable callback = callbacks.poll();
                if (callback == null) {
                    Thread.sleep(1);
                } else {
                    callback.run();
                }
            }
            return result.get(1, TimeUnit.SECONDS);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    interface IGreetingService {
        String greet(String name);
    }

    @DelegatesTo(IGreetingService.class)
    public static final class GenerationGreetingService extends GenerationOwnedDependencyAccess
            implements IGreetingService, DependencyAccess<String> {

        private final String greetingPrefix;

        public GenerationGreetingService() {
            this("Hello");
        }

        public GenerationGreetingService(String greetingPrefix) {
            this.greetingPrefix = greetingPrefix;
        }

        @Override
        public String greet(String name) {
            requireDependencyGenerationBound();
            return greetingPrefix + " " + name;
        }
    }

    // --------------------------------------------------------------------------------------------
    // 1. DI Generation handles fail closed on reload and unload
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DI Generation handles fail closed upon reload and unload")
    void testDiGenerationHandleFailsClosedOnReloadAndUnload() {
        ModuleId moduleId = ModuleId.of("greeting-feature");
        ModuleId systemId = ModuleId.of("greeting-system");

        ModuleDescriptor systemDesc = ModuleDescriptor.builder(systemId, ModulePrimaryType.SYSTEM, TARGET)
                .displayName("Greeting System")
                .build();

        ModuleDescriptor featureDesc = ModuleDescriptor.builder(moduleId, ModulePrimaryType.FEATURE, TARGET)
                .displayName("Greeting Feature")
                .parentSystemId(systemId)
                .reloadSupported(true)
                .build();

        AtomicInteger generationCounter = new AtomicInteger(1);
        AtomicReference<GenerationGreetingService> activeServiceRef = new AtomicReference<>();

        registry.registerModule(systemDesc, null);
        registry.registerModule(featureDesc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onStartAsync(IModuleContext context) {
                int gen = generationCounter.get();
                GenerationGreetingService service = new GenerationGreetingService("Hello-v" + gen);
                activeServiceRef.set(service);
                context.registerDependency(IGreetingService.class, service);
                return CompletableFuture.completedFuture(null);
            }
        });

        // 1. Enable module
        registry.enable(boot(systemId, ModuleLifecycleOperation.ENABLE));
        ModuleLifecycleResult enableRes = registry.enable(boot(moduleId, ModuleLifecycleOperation.ENABLE));
        assertTrue(enableRes.succeeded());
        assertEquals(1L, enableRes.lifecycleGeneration());

        GenerationGreetingService initialService = activeServiceRef.get();
        assertNotNull(initialService);
        assertEquals("Hello-v1 World", initialService.greet("World"));

        // 2. Reload module: generation increments, creates v2 service
        generationCounter.incrementAndGet();
        ModuleLifecycleResult reloadRes = registry.reload(req(moduleId, ModuleLifecycleOperation.RELOAD, 1L, 1L));
        assertTrue(reloadRes.succeeded());
        assertEquals(2L, reloadRes.lifecycleGeneration());

        GenerationGreetingService reloadedService = activeServiceRef.get();
        assertNotNull(reloadedService);
        assertNotSame(initialService, reloadedService);
        assertEquals("Hello-v2 World", reloadedService.greet("World"));

        // Retained old service handle FAILS CLOSED
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> initialService.greet("World"));
        assertTrue(ex.getMessage().contains("Dependency generation is not bound or has been unloaded"));

        // 3. Unload module
        ModuleLifecycleResult unloadRes = registry.unload(req(moduleId, ModuleLifecycleOperation.UNLOAD, 1L, 2L));
        assertTrue(unloadRes.succeeded());
        assertEquals(ModuleLifecycleState.UNLOADED, unloadRes.currentState());

        // Retained reloaded handle now FAILS CLOSED as well
        assertThrows(IllegalStateException.class, () -> reloadedService.greet("World"));
        assertNull(hostDependencyMap.findInstance(IGreetingService.class));
    }

    // --------------------------------------------------------------------------------------------
    // 2. Exact metadata ownership: old context never removes new replacement or unrelated token
    // --------------------------------------------------------------------------------------------

    interface ISharedToken {
        String id();
    }

    interface IUnrelatedToken {
        String tag();
    }

    @Test
    @DisplayName("Old context close never removes new replacement or unrelated module tokens; collisions rejected")
    void testOldContextNeverRemovesNewReplacementOrUnrelatedToken() {
        ModuleId modA = ModuleId.of("module-a");
        ModuleId modB = ModuleId.of("module-b");

        ModuleDescriptor descA = ModuleDescriptor.builder(modA, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor descB = ModuleDescriptor.builder(modB, ModulePrimaryType.SYSTEM, TARGET).build();

        registry.registerModule(descA, null);
        registry.registerModule(descB, null);

        registry.load(boot(modA, ModuleLifecycleOperation.LOAD));
        registry.load(boot(modB, ModuleLifecycleOperation.LOAD));

        ScopedModuleContext contextA = registry.findEntry(modA).orElseThrow().context();
        ScopedModuleContext contextB = registry.findEntry(modB).orElseThrow().context();

        ISharedToken tokenA1 = () -> "A1";
        ISharedToken tokenA2 = () -> "A2";
        IUnrelatedToken tokenB1 = () -> "B1";

        synchronized (registry.hostBoundary().monitor()) {
            // Register tokenA1 from contextA
            contextA.registerDependency(ISharedToken.class, tokenA1);
            assertEquals("A1", hostDependencyMap.getInstance(ISharedToken.class).id());

            // ContextB attempts to register the same token: REJECTED with collision
            assertThrows(IllegalStateException.class, () -> contextB.registerDependency(ISharedToken.class, () -> "Collision"));

            // ContextB cannot replace token registered by ContextA (borrowed token mutation rejected)
            assertThrows(IllegalStateException.class, () -> contextB.replaceDependency(ISharedToken.class, () -> "Borrowed"));

            // ContextB registers its own unrelated token
            contextB.registerDependency(IUnrelatedToken.class, tokenB1);
            assertEquals("B1", hostDependencyMap.getInstance(IUnrelatedToken.class).tag());

            // ContextA replaces its own tokenA1 with tokenA2
            contextA.replaceDependency(ISharedToken.class, tokenA2);
            assertEquals("A2", hostDependencyMap.getInstance(ISharedToken.class).id());

            // An external replacement or newer generation displaces tokenA2
            ISharedToken replacementA3 = () -> "A3";
            hostDependencyMap.replaceInstance(ISharedToken.class, () -> replacementA3);
            assertEquals("A3", hostDependencyMap.getInstance(ISharedToken.class).id());

            // CRITICAL INVARIANT: Closing old contextA NEVER removes the newer replacement A3 or unrelated token B1!
            assertDoesNotThrow(contextA::close);
            assertEquals("A3", hostDependencyMap.getInstance(ISharedToken.class).id());
            assertEquals("B1", hostDependencyMap.getInstance(IUnrelatedToken.class).tag());
        }
    }

    // --------------------------------------------------------------------------------------------
    // 3. Independent feature disable while preserving unrelated features
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Disabling one FEATURE leaves sibling FEATUREs operational")
    void testIndependentFeatureDisableAndPreserveUnrelatedFeatures() {
        ModuleId sysId = ModuleId.of("content-system");
        ModuleId discoveryId = ModuleId.of("content-discovery");
        ModuleId publishingId = ModuleId.of("content-publishing");

        ModuleDescriptor sysDesc = ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor discDesc = ModuleDescriptor.builder(discoveryId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .build();
        ModuleDescriptor pubDesc = ModuleDescriptor.builder(publishingId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .build();

        registry.registerModule(sysDesc, null);
        registry.registerModule(discDesc, null);
        registry.registerModule(pubDesc, null);

        registry.enable(boot(sysId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(discoveryId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(publishingId, ModuleLifecycleOperation.ENABLE));

        assertTrue(registry.findModule(discoveryId).orElseThrow().isActive());
        assertTrue(registry.findModule(publishingId).orElseThrow().isActive());

        // Disable discovery independently
        ModuleLifecycleResult disableDisc = registry.disable(req(discoveryId, ModuleLifecycleOperation.DISABLE, 1L, 1L));
        assertTrue(disableDisc.succeeded());
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(discoveryId).orElseThrow().state());

        // INVARIANT: publishing remains fully operational
        assertTrue(registry.findModule(publishingId).orElseThrow().isActive());
        assertTrue(registry.findModule(sysId).orElseThrow().isActive());
    }

    // --------------------------------------------------------------------------------------------
    // 4. System cascade disable and re-enable preserving independently-disabled desired state
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("System cascade disable and re-enable preserves independently-disabled child desired state")
    void testSystemCascadePreservesIndependentlyDisabledDesiredState() {
        ModuleId sysId = ModuleId.of("auto-post-system");
        ModuleId creativeId = ModuleId.of("auto-post-creative");
        ModuleId engagementId = ModuleId.of("auto-post-engagement");

        ModuleDescriptor sysDesc = ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor creativeDesc = ModuleDescriptor.builder(creativeId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .build();
        ModuleDescriptor engagementDesc = ModuleDescriptor.builder(engagementId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .build();

        registry.registerModule(sysDesc, null);
        registry.registerModule(creativeDesc, null);
        registry.registerModule(engagementDesc, null);

        registry.enable(boot(sysId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(creativeId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(engagementId, ModuleLifecycleOperation.ENABLE));

        // 1. Operator independently disables engagement
        registry.disable(req(engagementId, ModuleLifecycleOperation.DISABLE, 1L, 1L));
        assertEquals(ModuleDesiredState.DISABLED, registry.findModule(engagementId).orElseThrow().desiredState());
        assertEquals(ModuleDesiredState.ENABLED, registry.findModule(creativeId).orElseThrow().desiredState());

        // 2. Parent system is disabled: cascades down to all children
        long sysGen1 = registry.findModule(sysId).orElseThrow().configGeneration();
        registry.disable(req(sysId, ModuleLifecycleOperation.DISABLE, sysGen1, 1L));
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(sysId).orElseThrow().state());
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(creativeId).orElseThrow().state());
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(engagementId).orElseThrow().state());

        // INVARIANT: creative desired state remains ENABLED, engagement remains DISABLED!
        assertEquals(ModuleDesiredState.ENABLED, registry.findModule(creativeId).orElseThrow().desiredState());
        assertEquals(ModuleDesiredState.DISABLED, registry.findModule(engagementId).orElseThrow().desiredState());

        // 3. Parent system is re-enabled: cascades re-enable
        long sysGen2 = registry.findModule(sysId).orElseThrow().configGeneration();
        registry.enable(req(sysId, ModuleLifecycleOperation.ENABLE, sysGen2, 1L));
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(sysId).orElseThrow().state());

        // INVARIANT 1: creative (desired ENABLED) is re-enabled!
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(creativeId).orElseThrow().state());

        // CRITICAL INVARIANT 2: engagement (desired DISABLED) MUST REMAIN DISABLED!
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(engagementId).orElseThrow().state());
        assertEquals(ModuleDesiredState.DISABLED, registry.findModule(engagementId).orElseThrow().desiredState());
    }

    // --------------------------------------------------------------------------------------------
    // 5. Payment residual obligations preserved during and after disable
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Disabling Pay feature rejects new initiation while strictly preserving webhook, settlement, refund, recovery")
    void testPaymentResidualObligationsPreservedWhileDisabled() {
        ModuleId paySysId = ModuleId.of("tavall-pay");
        ModuleId stripeId = ModuleId.of("pay-stripe");

        CapabilityId newPaymentCap = CapabilityId.of("pay:new-initiation");
        CapabilityId webhookCap = CapabilityId.of("pay:webhook");
        CapabilityId settlementCap = CapabilityId.of("pay:settlement");
        CapabilityId refundCap = CapabilityId.of("pay:refund");
        CapabilityId recoveryCap = CapabilityId.of("pay:recovery");

        ModuleDescriptor sysDesc = ModuleDescriptor.builder(paySysId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor stripeDesc = ModuleDescriptor.builder(stripeId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(paySysId)
                .addProvidedCapability(newPaymentCap)
                .addProvidedCapability(webhookCap)
                .addProvidedCapability(settlementCap)
                .addProvidedCapability(refundCap)
                .addProvidedCapability(recoveryCap)
                .build();

        registry.registerModule(sysDesc, null);
        registry.registerModule(stripeDesc, () -> new IModuleLifecycleParticipant() {
            @Override
            public ResidualObligationPolicy residualObligationPolicy() {
                return ResidualObligationPolicy.of(webhookCap, settlementCap, refundCap, recoveryCap);
            }
        });

        registry.enable(boot(paySysId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(stripeId, ModuleLifecycleOperation.ENABLE));

        assertTrue(registry.isCapabilityAvailable(newPaymentCap));
        assertTrue(registry.isCapabilityAvailable(webhookCap));

        // Disable stripe (even forced disable must NOT bypass residual obligations)
        ModuleLifecycleResult disableRes = registry.disable(forcedReq(stripeId, ModuleLifecycleOperation.DISABLE, 1L, 1L, true));
        assertTrue(disableRes.succeeded());
        assertEquals(ModuleLifecycleState.DISABLED, registry.findModule(stripeId).orElseThrow().state());

        // CRITICAL INVARIANTS:
        // New transaction initiation is rejected
        assertFalse(registry.isCapabilityAvailable(newPaymentCap));

        // But residual settlement obligations remain serviceable!
        assertTrue(registry.isCapabilityAvailable(webhookCap));
        assertTrue(registry.isCapabilityAvailable(settlementCap));
        assertTrue(registry.isCapabilityAvailable(refundCap));
        assertTrue(registry.isCapabilityAvailable(recoveryCap));
    }

    // --------------------------------------------------------------------------------------------
    // 6. Non-reloadable and non-rollbackable typed failures
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Reload and rollback on unsupported modules return typed failure results")
    void testNonReloadableAndNonRollbackableTypedRejection() {
        ModuleId modId = ModuleId.of("strict-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET)
                .displayName("Strict Module")
                .build();
        assertFalse(desc.reloadSupported());
        assertFalse(desc.rollbackSupported());

        registry.registerModule(desc, null);
        registry.enable(boot(modId, ModuleLifecycleOperation.ENABLE));

        // Attempt reload: fails typed reload_not_supported
        ModuleLifecycleResult reloadRes = registry.reload(req(modId, ModuleLifecycleOperation.RELOAD, 1L, 1L));
        assertFalse(reloadRes.succeeded());
        assertEquals("reload_not_supported", reloadRes.failure().code());

        // Attempt rollback: fails typed rollback_not_supported
        ModuleLifecycleResult rollbackRes = registry.rollback(req(modId, ModuleLifecycleOperation.ROLLBACK, 1L, 1L));
        assertFalse(rollbackRes.succeeded());
        assertEquals("rollback_not_supported", rollbackRes.failure().code());
    }

    // --------------------------------------------------------------------------------------------
    // 7. Generation fences, blank actor rejection, and idempotency
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Generation fences reject stale requests and blank actor is rejected")
    void testGenerationFencesAndActorValidation() {
        ModuleId modId = ModuleId.of("fenced-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();
        registry.registerModule(desc, null);

        // Blank actor rejected
        assertThrows(IllegalArgumentException.class, () ->
                new ModuleLifecycleRequest(registry.runtimeEpochId(), "   ", modId, ModuleLifecycleOperation.LOAD, 1L, 0L, false, null, false)
        );

        // Load module
        registry.load(boot(modId, ModuleLifecycleOperation.LOAD));

        // Stale config generation
        ModuleLifecycleResult staleConfig = registry.enable(req(modId, ModuleLifecycleOperation.ENABLE, 99L, 1L));
        assertFalse(staleConfig.succeeded());
        assertEquals("stale_config_generation", staleConfig.failure().code());

        // Stale lifecycle generation
        ModuleLifecycleResult staleLifecycle = registry.enable(req(modId, ModuleLifecycleOperation.ENABLE, 1L, 99L));
        assertFalse(staleLifecycle.succeeded());
        assertEquals("stale_lifecycle_generation", staleLifecycle.failure().code());

        // Valid fences succeed
        ModuleLifecycleResult valid = registry.enable(req(modId, ModuleLifecycleOperation.ENABLE, 1L, 1L));
        assertTrue(valid.succeeded());
        assertTrue(valid.changed());

        // Idempotency: second enable returns changed=false
        ModuleLifecycleResult idempotent = registry.enable(req(modId, ModuleLifecycleOperation.ENABLE, 2L, 1L));
        assertTrue(idempotent.succeeded());
        assertFalse(idempotent.changed());
    }

    // --------------------------------------------------------------------------------------------
    // 8. Drain failure fails closed
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Failed drain fails closed: module state becomes FAILED and DI generation unbinds")
    void testFailedDrainFailsClosed() {
        ModuleId modId = ModuleId.of("failing-drain-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        registry.registerModule(desc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onDrainAsync(IModuleContext context) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("Network flush timeout during drain"));
                return failed;
            }
        });

        registry.enable(boot(modId, ModuleLifecycleOperation.ENABLE));
        ModuleLifecycleResult disableRes = registry.disable(req(modId, ModuleLifecycleOperation.DISABLE, 1L, 1L));

        assertFalse(disableRes.succeeded());
        assertEquals("module_drain_failed", disableRes.failure().code());
        assertEquals(ModuleLifecycleState.FAILED, registry.findModule(modId).orElseThrow().state());
    }

    // --------------------------------------------------------------------------------------------
    // 9. Aggregate health observation
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Optional module failure degrades system health, while required module failure marks system unhealthy")
    void testAggregateHealthObservation() {
        ModuleId sysId = ModuleId.of("health-system");
        ModuleId optId = ModuleId.of("optional-mod");
        ModuleId reqId = ModuleId.of("required-mod");

        ModuleDescriptor sysDesc = ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor optDesc = ModuleDescriptor.builder(optId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .required(false)
                .build();
        ModuleDescriptor reqDesc = ModuleDescriptor.builder(reqId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .required(true)
                .build();

        AtomicReference<ModuleHealth> optHealthRef = new AtomicReference<>(ModuleHealth.healthy("OK"));
        AtomicReference<ModuleHealth> reqHealthRef = new AtomicReference<>(ModuleHealth.healthy("OK"));

        registry.registerModule(sysDesc, null);
        registry.registerModule(optDesc, null, () -> optHealthRef.get());
        registry.registerModule(reqDesc, null, () -> reqHealthRef.get());

        // Initially healthy
        assertEquals(ModuleHealthStatus.HEALTHY, registry.checkAggregateHealth(Optional.of(sysId)).status());

        // Optional fails: system becomes DEGRADED
        optHealthRef.set(ModuleHealth.unhealthy("Cache connection lost", Map.of()));
        assertEquals(ModuleHealthStatus.DEGRADED, registry.checkAggregateHealth(Optional.of(sysId)).status());

        // Required fails: system becomes UNHEALTHY
        reqHealthRef.set(ModuleHealth.unhealthy("Database connection down", Map.of()));
        assertEquals(ModuleHealthStatus.UNHEALTHY, registry.checkAggregateHealth(Optional.of(sysId)).status());
    }

    // --------------------------------------------------------------------------------------------
    // 10. Force cannot bypass authorization or real dependencies
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Force cannot bypass authorization or real required dependencies")
    void testForceNeverBypassesAuthorizationOrRealDependencies() {
        ModuleLifecycleRegistry secureRegistry = new ModuleLifecycleRegistry(
                TARGET,
                hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                request -> CompletableFuture.completedFuture("authorized-admin".equals(request.actor())),
                snapshotProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        ModuleId depId = ModuleId.of("base-dependency");
        ModuleId featId = ModuleId.of("dependent-feature");

        ModuleDescriptor depDesc = ModuleDescriptor.builder(depId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor featDesc = ModuleDescriptor.builder(featId, ModulePrimaryType.SYSTEM, TARGET)
                .addRequiredDependency(depId)
                .build();

        secureRegistry.registerModule(depDesc, null);
        secureRegistry.registerModule(featDesc, null);

        // 1. Force with unauthorized actor: REJECTED with unauthorized
        ModuleLifecycleResult unauth = secureRegistry.enable(
                ModuleLifecycleRequest.forced(secureRegistry.runtimeEpochId(), "untrusted-actor", featId, ModuleLifecycleOperation.ENABLE, 1L, 0L, true)
        );
        assertFalse(unauth.succeeded());
        assertEquals("unauthorized", unauth.failure().code());

        // 2. Force with authorized actor, but dependency not loaded: REJECTED with missing_required_dependencies
        ModuleLifecycleResult depMissing = secureRegistry.enable(
                ModuleLifecycleRequest.forced(secureRegistry.runtimeEpochId(), "authorized-admin", featId, ModuleLifecycleOperation.ENABLE, 1L, 0L, true)
        );
        assertFalse(depMissing.succeeded());
        assertEquals("missing_required_dependencies", depMissing.failure().code());

        // 3. Load dependency, but not active. Force cannot bypass real required active dependencies!
        secureRegistry.load(boot(secureRegistry, "authorized-admin", depId, ModuleLifecycleOperation.LOAD));
        ModuleLifecycleResult depInactive = secureRegistry.enable(
                ModuleLifecycleRequest.forced(secureRegistry.runtimeEpochId(), "authorized-admin", featId, ModuleLifecycleOperation.ENABLE, 1L, 0L, true)
        );
        assertFalse(depInactive.succeeded());
        assertEquals("required_dependencies_not_active", depInactive.failure().code());
    }

    // --------------------------------------------------------------------------------------------
    // 11. Paper-safe host boundary enforcement
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Direct context mutation outside host lifecycle boundary monitor is rejected")
    void testPaperSafeHostBoundaryEnforcement() {
        ModuleId modId = ModuleId.of("boundary-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();
        registry.registerModule(desc, null);
        registry.load(boot(modId, ModuleLifecycleOperation.LOAD));

        ScopedModuleContext context = registry.findEntry(modId).orElseThrow().context();

        CompletableFuture<Throwable> violationFuture = new CompletableFuture<>();
        Thread background = new Thread(() -> {
            try {
                context.registerDependency(String.class, "Illegal");
                violationFuture.complete(null);
            } catch (Throwable t) {
                violationFuture.complete(t);
            }
        });
        background.start();

        Throwable caught = violationFuture.join();
        assertNotNull(caught);
        assertTrue(caught instanceof IllegalStateException);
        assertTrue(caught.getMessage().contains("Host serialization violation"));
    }

    // --------------------------------------------------------------------------------------------
    // 12. Wrong-thread call rejected even if monitor is held
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Host boundary rejects mutation from wrong thread even if monitor is held")
    void testWrongThreadCallRejectedEvenIfMonitorHeld() throws Exception {
        Thread designatedHostThread = Thread.currentThread();
        HostLifecycleBoundary threadBoundary = HostLifecycleBoundary.direct(designatedHostThread);
        ModuleLifecycleRegistry threadBoundRegistry = new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                TARGET,
                hostDependencyMap,
                threadBoundary,
                LifecycleTestPolicies.allowAll(),
                snapshotProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        ModuleId modId = ModuleId.of("thread-bound-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();
        threadBoundRegistry.registerModule(desc, null);
        threadBoundRegistry.load(boot(threadBoundRegistry, "tester", modId, ModuleLifecycleOperation.LOAD));

        ScopedModuleContext context = threadBoundRegistry.findEntry(modId).orElseThrow().context();

        CompletableFuture<Throwable> wrongThreadFuture = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            synchronized (threadBoundRegistry.hostBoundary().monitor()) {
                try {
                    context.registerDependency(String.class, "WrongThread");
                    wrongThreadFuture.complete(null);
                } catch (Throwable t) {
                    wrongThreadFuture.complete(t);
                }
            }
        });
        worker.start();

        Throwable caught = wrongThreadFuture.join();
        assertNotNull(caught);
        assertTrue(caught instanceof IllegalStateException);
        assertTrue(caught.getMessage().contains("Host serialization violation: thread is not designated host thread"));
        threadBoundRegistry.close();
    }

    // --------------------------------------------------------------------------------------------
    // 13. Queued owner-thread test with async worker drain
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Async drain completed on worker thread marshals cleanly to host thread")
    void testQueuedOwnerThreadWithAsyncWorkerDrain() throws Exception {
        Thread designatedHostThread = Thread.currentThread();
        OwnerThreadTestGateway hostExecutor = new OwnerThreadTestGateway(designatedHostThread);
        HostLifecycleBoundary boundary = new HostLifecycleBoundary(hostExecutor, thread -> thread == designatedHostThread);

        ModuleLifecycleRegistry marshaledRegistry = new ModuleLifecycleRegistry(
                TARGET,
                hostDependencyMap,
                boundary,
                LifecycleTestPolicies.allowAll(),
                snapshotProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        ModuleId modId = ModuleId.of("async-worker-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        AtomicBoolean workerDrainExecuted = new AtomicBoolean(false);
        AtomicReference<Thread> drainCompletionThread = new AtomicReference<>();

        marshaledRegistry.registerModule(desc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onDrainAsync(IModuleContext context) {
                CompletableFuture<Void> stage = new CompletableFuture<>();
                // Simulate off-host worker thread completing drain
                new Thread(() -> {
                    workerDrainExecuted.set(true);
                    drainCompletionThread.set(Thread.currentThread());
                    stage.complete(null);
                }).start();
                return stage;
            }
        });

        marshaledRegistry.enable(boot(marshaledRegistry, "tester", modId, ModuleLifecycleOperation.ENABLE));
        ModuleLifecycleResult disableRes = hostExecutor.await(marshaledRegistry.disableAsync(
                ModuleLifecycleRequest.request(marshaledRegistry.runtimeEpochId(), "tester", modId, ModuleLifecycleOperation.DISABLE, 1L, 1L)
        ));

        assertTrue(disableRes.succeeded());
        assertTrue(workerDrainExecuted.get());
        assertNotSame(designatedHostThread, drainCompletionThread.get());
        assertEquals(ModuleLifecycleState.DISABLED, marshaledRegistry.findModule(modId).orElseThrow().state());
        marshaledRegistry.close();
    }

    // --------------------------------------------------------------------------------------------
    // Rejected dispatch must settle the operation instead of leaving an orphaned future.
    @Test
    void rejectedHostDispatchCompletesOperationWithoutMutation() throws Exception {
        AtomicBoolean reject = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        HostLifecycleBoundary boundary = new HostLifecycleBoundary(action -> {
            if (reject.get()) {
                throw new java.util.concurrent.RejectedExecutionException("fixture host stopped");
            }
            action.run();
        }, thread -> thread == owner);
        ModuleLifecycleRegistry controlled = new ModuleLifecycleRegistry(TARGET, hostDependencyMap,
                boundary, LifecycleTestPolicies.allowAll(), snapshotProvider,
                LifecycleTestPolicies.acceptAllRollbacks());
        ModuleId id = ModuleId.of("rejected-dispatch");
        controlled.registerModule(ModuleDescriptor.builder(id, ModulePrimaryType.SYSTEM, TARGET).build(), null);
        reject.set(true);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> controlled.loadAsync(
                boot(controlled, "tester", id, ModuleLifecycleOperation.LOAD)).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(ModuleLifecycleState.DISCOVERED, controlled.findModule(id).orElseThrow().state());
        reject.set(false);
        controlled.closeAsync().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    // 14. Deadlock prevention: no blocking sync owner call
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Synchronous convenience call on host thread with pending async completion fails fast without deadlock")
    void testNoBlockingSyncOwnerCallDeadlockPrevention() throws Exception {
        Thread designatedHostThread = Thread.currentThread();
        OwnerThreadTestGateway queuedExecutor = new OwnerThreadTestGateway(designatedHostThread);
        HostLifecycleBoundary boundary = new HostLifecycleBoundary(queuedExecutor, thread -> thread == designatedHostThread);

        ModuleLifecycleRegistry hostRegistry = new ModuleLifecycleRegistry(
                TARGET,
                hostDependencyMap,
                boundary,
                LifecycleTestPolicies.allowAll(),
                snapshotProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        ModuleId modId = ModuleId.of("deadlock-test-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        CompletableFuture<Void> lingeringDrain = new CompletableFuture<>();
        hostRegistry.registerModule(desc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onDrainAsync(IModuleContext context) {
                return lingeringDrain;
            }
        });

        // Initialize enabled
        queuedExecutor.await(hostRegistry.enableAsync(boot(hostRegistry, "tester", modId, ModuleLifecycleOperation.ENABLE)));

        // Invoking synchronous disable on the designated host thread while async drain is pending
        // MUST NOT DEADLOCK! It must fail fast with sync_host_blocking_forbidden.
        ModuleLifecycleResult syncResult = hostRegistry.disable(
                ModuleLifecycleRequest.request(hostRegistry.runtimeEpochId(), "tester", modId, ModuleLifecycleOperation.DISABLE, 1L, 1L)
        );

        assertFalse(syncResult.succeeded());
        assertEquals("sync_host_blocking_forbidden", syncResult.failure().code());

        // Clean up lingering future
        lingeringDrain.complete(null);
        queuedExecutor.await(hostRegistry.closeAsync());
    }

    // --------------------------------------------------------------------------------------------
    // 15. Fail-closed unconfigured coordinator
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("No-arg constructor component is unconfigured and fail-closed")
    void testFailClosedUnconfiguredCoordinator() {
        ModuleLifecycleRegistry unconfigured = new ModuleLifecycleRegistry();
        assertFalse(unconfigured.isConfigured());

        ModuleId modId = ModuleId.of("unconfigured-mod");
        ModuleLifecycleRequest request = ModuleLifecycleRequest.request(
                unconfigured.runtimeEpochId(), "tester", modId, ModuleLifecycleOperation.ENABLE, 1L, 0L
        );

        ModuleLifecycleResult result = unconfigured.enable(request);
        assertFalse(result.succeeded());
        assertEquals("unconfigured_lifecycle_coordinator", result.failure().code());
    }

    // --------------------------------------------------------------------------------------------
    // 16. Replay conflict & duplicate reload releasing zero extra generations
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate reload with identical correlation token returns cached result releasing zero extra generations")
    void testReplayConflictAndDuplicateReloadZeroGenerations() {
        ModuleId modId = ModuleId.of("dedup-reload-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET)
                .reloadSupported(true)
                .build();

        AtomicInteger generationCounter = new AtomicInteger(1);
        registry.registerModule(desc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onLoadAsync(IModuleContext context) {
                generationCounter.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        });

        registry.enable(boot(modId, ModuleLifecycleOperation.ENABLE));
        assertEquals(1L, registry.findModule(modId).orElseThrow().lifecycleGeneration());

        // First reload with explicit client correlation token
        String clientToken = "client-token-42";
        ModuleLifecycleRequest reloadReq1 = ModuleLifecycleRequest.request(
                registry.runtimeEpochId(), "operator", modId, ModuleLifecycleOperation.RELOAD, 1L, 1L, clientToken
        );
        ModuleLifecycleResult reloadRes1 = registry.reload(reloadReq1);
        assertTrue(reloadRes1.succeeded());
        assertTrue(reloadRes1.changed());
        assertEquals(2L, reloadRes1.lifecycleGeneration());

        // Duplicate reload with exact same correlation token
        ModuleLifecycleRequest duplicateReload = ModuleLifecycleRequest.request(
                registry.runtimeEpochId(), "operator", modId, ModuleLifecycleOperation.RELOAD, 1L, 1L, clientToken
        );
        ModuleLifecycleResult reloadRes2 = registry.reload(duplicateReload);

        // Deduplication succeeds, returns changed=false, and DOES NOT release a 3rd generation!
        assertTrue(reloadRes2.succeeded());
        assertFalse(reloadRes2.changed());
        assertEquals(2L, registry.findModule(modId).orElseThrow().lifecycleGeneration());
    }

    // --------------------------------------------------------------------------------------------
    // 17. Failed child cascade reports honest failure and fails closed
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Child feature drain failure during system cascade reports honest partial failure")
    void testFailedChildCascadeHonestFailureAndFailClosed() {
        ModuleId sysId = ModuleId.of("cascade-sys");
        ModuleId childId = ModuleId.of("failing-child");

        ModuleDescriptor sysDesc = ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build();
        ModuleDescriptor childDesc = ModuleDescriptor.builder(childId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(sysId)
                .build();

        registry.registerModule(sysDesc, null);
        registry.registerModule(childDesc, () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onDrainAsync(IModuleContext context) {
                CompletableFuture<Void> fail = new CompletableFuture<>();
                fail.completeExceptionally(new RuntimeException("Child drain disk error"));
                return fail;
            }
        });

        registry.enable(boot(sysId, ModuleLifecycleOperation.ENABLE));
        registry.enable(boot(childId, ModuleLifecycleOperation.ENABLE));

        // Disabling system cascades to child; child drain fails
        ModuleLifecycleResult sysDisable = registry.disable(req(sysId, ModuleLifecycleOperation.DISABLE, 1L, 1L));

        assertFalse(sysDisable.succeeded());
        assertEquals("child_cascade_drain_failed", sysDisable.failure().code());
        // Child failed closed
        assertEquals(ModuleLifecycleState.FAILED, registry.findModule(childId).orElseThrow().state());
    }

    // --------------------------------------------------------------------------------------------
    // 18. Automatic snapshot restoration on startup
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Coordinator automatically restores desired states from snapshot provider upon boot")
    void testAutomaticSnapshotRestorationOnRestart() {
        ModuleId modId = ModuleId.of("restarted-module");
        // Pre-seed snapshot provider with previous desiredState = DISABLED
        snapshotProvider.saveSnapshot(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 5L, Map.of(modId, ModuleDesiredState.DISABLED)));

        ModuleLifecycleRegistry freshRegistry = createTestRegistry(Runnable::run);
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        freshRegistry.registerModule(desc, null);

        // Desired state is restored automatically to DISABLED without manual createSnapshot or restore call!
        assertEquals(ModuleDesiredState.DISABLED, freshRegistry.findModule(modId).orElseThrow().desiredState());
        assertEquals(5L, freshRegistry.findModule(modId).orElseThrow().configGeneration());
    }

    // --------------------------------------------------------------------------------------------
    // 19. Stale old-boot mutation rejected
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Mutation request targeting stale runtimeEpochId is rejected with stale_runtime_epoch")
    void testStaleOldBootMutationRejected() {
        ModuleId modId = ModuleId.of("epoch-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();
        registry.registerModule(desc, null);

        UUID staleEpochId = UUID.randomUUID(); // Different epoch from registry.runtimeEpochId()
        ModuleLifecycleRequest staleRequest = ModuleLifecycleRequest.request(
                staleEpochId, "tester", modId, ModuleLifecycleOperation.ENABLE, 1L, 0L
        );

        ModuleLifecycleResult result = registry.enable(staleRequest);
        assertFalse(result.succeeded());
        assertEquals("stale_runtime_epoch", result.failure().code());
    }

    // --------------------------------------------------------------------------------------------
    // 20. Incompatible rollback pre-mutation rejected
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Incompatible rollback target is rejected before any state mutation or config generation advance")
    void testIncompatibleRollbackPreMutationRejected() {
        IRollbackCompatibilityValidator strictValidator = (target, currentGen, candidate, descriptors) ->
                CompletableFuture.completedFuture(IRollbackCompatibilityValidator.ValidationResult.incompatible("Schema v1 is incompatible with v2"));

        ModuleLifecycleRegistry rollbackRegistry = new ModuleLifecycleRegistry(
                TARGET,
                hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(),
                snapshotProvider,
                strictValidator
        );

        ModuleId modId = ModuleId.of("rollback-mod");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET)
                .rollbackSupported(true)
                .build();

        rollbackRegistry.registerModule(desc, null);
        rollbackRegistry.enable(boot(rollbackRegistry, "tester", modId, ModuleLifecycleOperation.ENABLE));

        // Pre-seed a candidate snapshot
        snapshotProvider.saveSnapshot(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 1L, Map.of(modId, ModuleDesiredState.DISABLED)));

        // Attempt rollback
        ModuleLifecycleResult rollbackRes = rollbackRegistry.rollback(
                ModuleLifecycleRequest.request(rollbackRegistry.runtimeEpochId(), "tester", modId, ModuleLifecycleOperation.ROLLBACK, 1L, 1L)
        );

        assertFalse(rollbackRes.succeeded());
        assertEquals("incompatible_rollback_target", rollbackRes.failure().code());

        // INVARIANT: State and config generation remain completely unmutated!
        assertEquals(ModuleLifecycleState.ENABLED, rollbackRegistry.findModule(modId).orElseThrow().state());
        assertEquals(1L, rollbackRegistry.findModule(modId).orElseThrow().configGeneration());
        rollbackRegistry.close();
    }

    // --------------------------------------------------------------------------------------------
    // 21. Delayed external-thread snapshot load with queued owner boundary
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Coordinator waits for asynchronous delayed snapshot load; queries reject before ready")
    void testDelayedExternalThreadSnapshotLoad() throws Exception {
        Thread ownerThread = Thread.currentThread();
        OwnerThreadTestGateway gateway = new OwnerThreadTestGateway(ownerThread);
        HostLifecycleBoundary boundary = new HostLifecycleBoundary(gateway, thread -> thread == ownerThread);

        CompletableFuture<Optional<ModuleDesiredStateSnapshot>> delayedLoad = new CompletableFuture<>();
        IModuleDesiredStateSnapshotProvider delayedProvider = new IModuleDesiredStateSnapshotProvider() {
            @Override
            public CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target) {
                return delayedLoad;
            }

            @Override
            public CompletionStage<Boolean> casSnapshotAsync(RuntimeTarget target, long expectedConfigGeneration, ModuleDesiredStateSnapshot newSnapshot) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {}

            @Override
            public boolean isProductionPersistent() {
                return false;
            }
        };

        ModuleLifecycleRegistry delayedRegistry = new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                TARGET,
                hostDependencyMap,
                boundary,
                LifecycleTestPolicies.allowAll(),
                delayedProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        ModuleId modId = ModuleId.of("delayed-module");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        // While snapshot load is still delayed:
        assertFalse(delayedRegistry.isReady());
        assertThrows(IllegalStateException.class, () -> delayedRegistry.registerModule(desc, null));
        assertThrows(IllegalStateException.class, () -> delayedRegistry.findModule(modId));
        assertThrows(IllegalStateException.class, () -> delayedRegistry.listModules());

        // Complete snapshot load from a background thread
        ModuleDesiredStateSnapshot snapshot = ModuleDesiredStateSnapshot.now(TARGET, 4L, Map.of(modId, ModuleDesiredState.DISABLED));
        new Thread(() -> delayedLoad.complete(Optional.of(snapshot))).start();

        // Await readiness through owner gateway pumping
        gateway.await(delayedRegistry.whenReady());
        assertTrue(delayedRegistry.isReady());

        // Now registrations and queries succeed
        delayedRegistry.registerModule(desc, null);
        assertEquals(ModuleDesiredState.DISABLED, delayedRegistry.findModule(modId).orElseThrow().desiredState());
        assertEquals(4L, delayedRegistry.findModule(modId).orElseThrow().configGeneration());

        // Lifecycle mutation now executes and succeeds on the owner thread
        ModuleLifecycleResult loadResult = gateway.await(delayedRegistry.loadAsync(
                boot(delayedRegistry, "tester", modId, ModuleLifecycleOperation.LOAD)
        ));
        assertTrue(loadResult.succeeded());
        assertEquals(ModuleLifecycleState.LOADED, loadResult.currentState());

        delayedRegistry.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // --------------------------------------------------------------------------------------------
    // 22. Failed initial snapshot CAS fails closed
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Coordinator fails closed when initial snapshot CAS is rejected")
    void testFailedInitialCasFailsClosed() throws Exception {
        IModuleDesiredStateSnapshotProvider failedCasProvider = new IModuleDesiredStateSnapshotProvider() {
            @Override
            public CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target) {
                return CompletableFuture.completedFuture(Optional.empty()); // Triggers initial CAS 0L -> 1L
            }

            @Override
            public CompletionStage<Boolean> casSnapshotAsync(RuntimeTarget target, long expectedConfigGeneration, ModuleDesiredStateSnapshot newSnapshot) {
                return CompletableFuture.completedFuture(false); // CAS fails!
            }

            @Override
            public void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {}

            @Override
            public boolean isProductionPersistent() {
                return false;
            }
        };

        ModuleLifecycleRegistry failingRegistry = new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                TARGET,
                hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(),
                failedCasProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        // Readiness must fail exceptionally
        assertTrue(failingRegistry.whenReady().toCompletableFuture().isCompletedExceptionally());
        assertFalse(failingRegistry.isReady());

        ModuleId modId = ModuleId.of("failing-mod");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        // Registration throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> failingRegistry.registerModule(desc, null));

        // Queries throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> failingRegistry.listModules());

        // Lifecycle operations return typed initialization_failed failure
        ModuleLifecycleResult result = failingRegistry.load(boot(failingRegistry, "tester", modId, ModuleLifecycleOperation.LOAD));
        assertFalse(result.succeeded());
        assertEquals("initialization_failed", result.failure().code());
    }

    // --------------------------------------------------------------------------------------------
    // 23. Exception during snapshot loading fails closed
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Coordinator fails closed when snapshot provider throws exception on load")
    void testExceptionDuringSnapshotLoadFailsClosed() throws Exception {
        IModuleDesiredStateSnapshotProvider errorProvider = new IModuleDesiredStateSnapshotProvider() {
            @Override
            public CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target) {
                return CompletableFuture.failedFuture(new java.io.IOException("Persistent disk storage corrupted"));
            }

            @Override
            public CompletionStage<Boolean> casSnapshotAsync(RuntimeTarget target, long expectedConfigGeneration, ModuleDesiredStateSnapshot newSnapshot) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {}

            @Override
            public boolean isProductionPersistent() {
                return false;
            }
        };

        ModuleLifecycleRegistry errorRegistry = new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                TARGET,
                hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(),
                errorProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        assertTrue(errorRegistry.whenReady().toCompletableFuture().isCompletedExceptionally());
        assertFalse(errorRegistry.isReady());

        ModuleId modId = ModuleId.of("err-mod");
        assertThrows(IllegalStateException.class, () -> errorRegistry.findModule(modId));

        ModuleLifecycleResult result = errorRegistry.enable(boot(errorRegistry, "tester", modId, ModuleLifecycleOperation.ENABLE));
        assertFalse(result.succeeded());
        assertEquals("initialization_failed", result.failure().code());
        assertTrue(result.failure().message().contains("Persistent disk storage corrupted")
                || result.failure().details().contains("Persistent disk storage corrupted"));
    }

    // --------------------------------------------------------------------------------------------
    // 24. Wrong-target snapshot fails closed
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Coordinator fails closed if snapshot provider returns snapshot for different runtime target")
    void testWrongTargetSnapshotFailsClosed() throws Exception {
        RuntimeTarget wrongTarget = RuntimeTarget.of("foreign-runtime");
        IModuleDesiredStateSnapshotProvider foreignProvider = new IModuleDesiredStateSnapshotProvider() {
            @Override
            public CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target) {
                ModuleDesiredStateSnapshot foreignSnapshot = ModuleDesiredStateSnapshot.now(wrongTarget, 2L, Map.of());
                return CompletableFuture.completedFuture(Optional.of(foreignSnapshot));
            }

            @Override
            public CompletionStage<Boolean> casSnapshotAsync(RuntimeTarget target, long expectedConfigGeneration, ModuleDesiredStateSnapshot newSnapshot) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {}

            @Override
            public boolean isProductionPersistent() {
                return false;
            }
        };

        ModuleLifecycleRegistry wrongTargetRegistry = new ModuleLifecycleRegistry(
                UUID.randomUUID(),
                TARGET,
                hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(),
                foreignProvider,
                LifecycleTestPolicies.acceptAllRollbacks()
        );

        assertTrue(wrongTargetRegistry.whenReady().toCompletableFuture().isCompletedExceptionally());
        assertFalse(wrongTargetRegistry.isReady());

        ModuleId modId = ModuleId.of("target-mismatch-mod");
        ModuleLifecycleResult result = wrongTargetRegistry.load(boot(wrongTargetRegistry, "tester", modId, ModuleLifecycleOperation.LOAD));
        assertFalse(result.succeeded());
        assertEquals("initialization_failed", result.failure().code());
        assertTrue(result.failure().message().contains("Snapshot runtime target mismatch")
                || result.failure().details().contains("Snapshot runtime target mismatch"));
    }

    // --------------------------------------------------------------------------------------------
    // 25. Two independent simulated stores never cross-contaminate
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Two independent InMemorySnapshotStore instances never cross-contaminate")
    void testIndependentStoresNeverCrossContaminate() throws Exception {
        InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore storeA = new InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore();
        InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore storeB = new InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore();

        InMemoryDesiredStateSnapshotProvider providerA = new InMemoryDesiredStateSnapshotProvider(storeA);
        InMemoryDesiredStateSnapshotProvider providerB = new InMemoryDesiredStateSnapshotProvider(storeB);

        ModuleId modId = ModuleId.of("isolation-module");
        ModuleDesiredStateSnapshot snapA = ModuleDesiredStateSnapshot.now(TARGET, 10L, Map.of(modId, ModuleDesiredState.DISABLED));

        providerA.saveSnapshot(TARGET, snapA);

        assertEquals(1, storeA.size());
        assertEquals(0, storeB.size());
        assertTrue(providerA.loadSnapshotAsync(TARGET).toCompletableFuture().get().isPresent());
        assertTrue(providerB.loadSnapshotAsync(TARGET).toCompletableFuture().get().isEmpty());

        ModuleDesiredStateSnapshot snapB = ModuleDesiredStateSnapshot.now(TARGET, 20L, Map.of(modId, ModuleDesiredState.ENABLED));
        providerB.saveSnapshot(TARGET, snapB);

        assertEquals(10L, providerA.loadSnapshotAsync(TARGET).toCompletableFuture().get().get().configGeneration());
        assertEquals(20L, providerB.loadSnapshotAsync(TARGET).toCompletableFuture().get().get().configGeneration());
    }

    // --------------------------------------------------------------------------------------------
    // 26. Simulated controller restart sharing explicit store retains disabled states
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Controller restart sharing explicit store retains disabled states without manual save")
    void testRestartSharingExplicitStoreRetainsDisabledStates() throws Exception {
        InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore sharedStore = new InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore();

        ModuleId modId = ModuleId.of("cluster-service");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();

        // 1. First controller instance boots
        InMemoryDesiredStateSnapshotProvider provider1 = new InMemoryDesiredStateSnapshotProvider(sharedStore);
        ModuleLifecycleRegistry controller1 = new ModuleLifecycleRegistry(
                UUID.randomUUID(), TARGET, hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(), provider1, LifecycleTestPolicies.acceptAllRollbacks()
        );
        controller1.registerModule(desc, null);
        controller1.enable(boot(controller1, "tester", modId, ModuleLifecycleOperation.ENABLE));
        assertEquals(ModuleLifecycleState.ENABLED, controller1.findModule(modId).orElseThrow().state());

        // Explicitly disable the module: updates desired state to DISABLED and CAS-persists snapshot
        ModuleLifecycleResult disableRes = controller1.disable(
                ModuleLifecycleRequest.request(controller1.runtimeEpochId(), "tester", modId, ModuleLifecycleOperation.DISABLE, 1L, 1L)
        );
        assertTrue(disableRes.succeeded());
        assertEquals(ModuleDesiredState.DISABLED, controller1.findModule(modId).orElseThrow().desiredState());
        long finalGen = disableRes.configGeneration();

        controller1.close();

        // 2. Second controller instance boots, reusing the SAME sharedStore authority
        InMemoryDesiredStateSnapshotProvider provider2 = new InMemoryDesiredStateSnapshotProvider(sharedStore);
        ModuleLifecycleRegistry controller2 = new ModuleLifecycleRegistry(
                UUID.randomUUID(), TARGET, hostDependencyMap,
                HostLifecycleBoundary.direct(Thread.currentThread()),
                LifecycleTestPolicies.allowAll(), provider2, LifecycleTestPolicies.acceptAllRollbacks()
        );
        controller2.registerModule(desc, null);

        // Desired state MUST be automatically DISABLED without manual save call!
        assertEquals(ModuleDesiredState.DISABLED, controller2.findModule(modId).orElseThrow().desiredState());
        assertEquals(finalGen, controller2.findModule(modId).orElseThrow().configGeneration());

        controller2.close();
    }

    // --------------------------------------------------------------------------------------------
    // 27. Atomic concurrent CAS across 2 adapter instances sharing store
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent CAS across multiple adapter instances sharing store is atomic")
    void testAtomicConcurrentCasSharingStore() throws Exception {
        InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore sharedStore = new InMemoryDesiredStateSnapshotProvider.InMemorySnapshotStore();
        sharedStore.save(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 1L, Map.of()));

        InMemoryDesiredStateSnapshotProvider adapter1 = new InMemoryDesiredStateSnapshotProvider(sharedStore);
        InMemoryDesiredStateSnapshotProvider adapter2 = new InMemoryDesiredStateSnapshotProvider(sharedStore);

        ModuleId modId = ModuleId.of("race-module");
        ModuleDesiredStateSnapshot snap1 = ModuleDesiredStateSnapshot.now(TARGET, 2L, Map.of(modId, ModuleDesiredState.ENABLED));
        ModuleDesiredStateSnapshot snap2 = ModuleDesiredStateSnapshot.now(TARGET, 2L, Map.of(modId, ModuleDesiredState.DISABLED));

        CompletableFuture<Boolean> cas1 = adapter1.casSnapshotAsync(TARGET, 1L, snap1).toCompletableFuture();
        CompletableFuture<Boolean> cas2 = adapter2.casSnapshotAsync(TARGET, 1L, snap2).toCompletableFuture();

        boolean res1 = cas1.get(5, TimeUnit.SECONDS);
        boolean res2 = cas2.get(5, TimeUnit.SECONDS);

        // Exactly one CAS succeeds, exactly one fails!
        assertTrue(res1 ^ res2, "Expected exactly one CAS to succeed and one to fail");

        // Stored snapshot is at generation 2
        assertEquals(2L, sharedStore.load(TARGET).orElseThrow().configGeneration());
    }

    // --------------------------------------------------------------------------------------------
    // 28. Public restoreFromSnapshotAsync validates target, generation monotonicity, and store CAS
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Public restoreFromSnapshotAsync enforces target match, monotonic generation, and store CAS")
    void testPublicRestoreFromSnapshotAsyncEnforcesInvariants() throws Exception {
        ModuleId modId = ModuleId.of("restore-test-mod");
        ModuleDescriptor desc = ModuleDescriptor.builder(modId, ModulePrimaryType.SYSTEM, TARGET).build();
        registry.registerModule(desc, null);

        // 1. Target mismatch rejected
        ModuleDesiredStateSnapshot wrongTargetSnap = ModuleDesiredStateSnapshot.now(
                RuntimeTarget.of("other-runtime"), 5L, Map.of(modId, ModuleDesiredState.DISABLED)
        );
        ExecutionException ex1 = assertThrows(ExecutionException.class, () ->
                registry.restoreFromSnapshotAsync(wrongTargetSnap).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(ex1.getCause() instanceof IllegalArgumentException);
        assertTrue(ex1.getCause().getMessage().contains("Snapshot runtime target mismatch"));

        // 2. Lowering configuration generation rejected (current is 1L)
        ModuleDesiredStateSnapshot lowerGenSnap = ModuleDesiredStateSnapshot.now(
                TARGET, 0L, Map.of(modId, ModuleDesiredState.DISABLED)
        );
        ExecutionException ex2 = assertThrows(ExecutionException.class, () ->
                registry.restoreFromSnapshotAsync(lowerGenSnap).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(ex2.getCause() instanceof IllegalArgumentException);
        assertTrue(ex2.getCause().getMessage().contains("lower than current"));

        // 3. Store CAS failure rejects restoration without mutating coordinator state
        // Mutate store out-of-band to simulate concurrent CAS advance by another controller
        sharedSnapshotStore.save(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 99L, Map.of()));

        ModuleDesiredStateSnapshot candidateSnap = ModuleDesiredStateSnapshot.now(
                TARGET, 2L, Map.of(modId, ModuleDesiredState.DISABLED)
        );
        ExecutionException ex3 = assertThrows(ExecutionException.class, () ->
                registry.restoreFromSnapshotAsync(candidateSnap).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(ex3.getCause() instanceof IllegalStateException);
        assertTrue(ex3.getCause().getMessage().contains("Snapshot store CAS failed during restore"));

        // State remains unmutated at initial generation
        assertEquals(1L, registry.findModule(modId).orElseThrow().configGeneration());

        // 4. Valid CAS succeeds and updates state
        sharedSnapshotStore.save(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 1L, Map.of()));
        registry.restoreFromSnapshotAsync(candidateSnap).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(2L, registry.findModule(modId).orElseThrow().configGeneration());
        assertEquals(ModuleDesiredState.DISABLED, registry.findModule(modId).orElseThrow().desiredState());
    }

    // --------------------------------------------------------------------------------------------
    // v5 regressions: honest cascade results, dependency fences, drained unload, snapshot authority
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SYSTEM enable reports failure when an eligible child FEATURE fails to start")
    void systemEnableReportsChildFeatureFailure() {
        ModuleId systemId = ModuleId.of("cascade-system");
        ModuleId healthyId = ModuleId.of("cascade-healthy");
        ModuleId brokenId = ModuleId.of("cascade-broken");
        registry.registerModule(ModuleDescriptor.builder(systemId, ModulePrimaryType.SYSTEM, TARGET).build(), null);
        registry.registerModule(ModuleDescriptor.builder(healthyId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(systemId).build(), null);
        registry.registerModule(ModuleDescriptor.builder(brokenId, ModulePrimaryType.FEATURE, TARGET)
                .parentSystemId(systemId).build(), () -> new IModuleLifecycleParticipant() {
            @Override
            public CompletionStage<Void> onStartAsync(IModuleContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"));
            }
        });
        ModuleLifecycleResult result = registry.enable(req(systemId, ModuleLifecycleOperation.ENABLE, 1L, 0L));

        assertFalse(result.succeeded(), "a SYSTEM must not report success while a child FEATURE failed");
        assertEquals("child_cascade_enable_failed", result.failure().code());
        assertTrue(result.failure().message().contains(brokenId.toString()));
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(healthyId).orElseThrow().state());
    }

    @Test
    @DisplayName("Disabling a required dependency fails closed while an enabled dependent remains")
    void disablingRequiredDependencyFailsClosed() {
        ModuleId libraryId = ModuleId.of("shared-dependency");
        ModuleId consumerId = ModuleId.of("dependent-consumer");
        registry.registerModule(ModuleDescriptor.builder(libraryId, ModulePrimaryType.API, TARGET).build(), null);
        registry.registerModule(ModuleDescriptor.builder(consumerId, ModulePrimaryType.APPLICATION, TARGET)
                .addRequiredDependency(libraryId).build(), null);
        assertTrue(registry.enable(req(libraryId, ModuleLifecycleOperation.ENABLE, 1L, 0L)).succeeded());
        assertTrue(registry.enable(req(consumerId, ModuleLifecycleOperation.ENABLE, 2L, 0L)).succeeded());

        ModuleLifecycleResult refused = registry.disable(req(libraryId, ModuleLifecycleOperation.DISABLE, 3L, 1L));
        assertFalse(refused.succeeded());
        assertEquals("required_by_active_dependents", refused.failure().code());
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(libraryId).orElseThrow().state());

        assertTrue(registry.disable(req(consumerId, ModuleLifecycleOperation.DISABLE, 3L, 1L)).succeeded());
        assertTrue(registry.disable(req(libraryId, ModuleLifecycleOperation.DISABLE, 4L, 1L)).succeeded());
    }

    @Test
    @DisplayName("Unloading an active module quiesces and drains before closing its generation")
    void unloadingActiveModuleDrainsBeforeClose() {
        ModuleId moduleId = ModuleId.of("drained-unload");
        List<String> phases = new ArrayList<>();
        registry.registerModule(ModuleDescriptor.builder(moduleId, ModulePrimaryType.APPLICATION, TARGET).build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onQuiesceAsync(IModuleContext context) {
                        phases.add("quiesce");
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> onDrainAsync(IModuleContext context) {
                        phases.add("drain");
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        phases.add("close");
                        return CompletableFuture.completedFuture(null);
                    }
                });
        assertTrue(registry.enable(req(moduleId, ModuleLifecycleOperation.ENABLE, 1L, 0L)).succeeded());

        ModuleLifecycleResult unloaded = registry.unload(req(moduleId, ModuleLifecycleOperation.UNLOAD, 2L, 1L));

        assertTrue(unloaded.succeeded());
        assertEquals(ModuleLifecycleState.UNLOADED, unloaded.currentState());
        assertEquals(List.of("quiesce", "drain", "close"), phases);
    }

    @Test
    @DisplayName("Desired-state CAS preserves the persisted state of modules not registered in this generation")
    void desiredStateCasPreservesUnregisteredModuleState() throws Exception {
        registry.close();
        ModuleId absentFeature = ModuleId.of("absent-disabled-feature");
        ModuleId present = ModuleId.of("present-module");
        sharedSnapshotStore.save(TARGET, ModuleDesiredStateSnapshot.now(TARGET, 3L,
                Map.of(absentFeature, ModuleDesiredState.DISABLED)));
        registry = createTestRegistry(Runnable::run);
        registry.whenReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        registry.registerModule(ModuleDescriptor.builder(present, ModulePrimaryType.APPLICATION, TARGET).build(), null);

        assertTrue(registry.enable(req(present, ModuleLifecycleOperation.ENABLE, 3L, 0L)).succeeded());

        ModuleDesiredStateSnapshot stored = sharedSnapshotStore.load(TARGET).orElseThrow();
        assertEquals(4L, stored.configGeneration());
        assertEquals(ModuleDesiredState.DISABLED, stored.desiredStates().get(absentFeature));
        assertEquals(ModuleDesiredState.ENABLED, stored.desiredStates().get(present));
    }

    @Test
    @DisplayName("An exceptionally completed operation releases its in-flight reservation")
    void exceptionalCompletionReleasesInFlightReservation() throws Exception {
        registry.close();
        AtomicBoolean rejectNext = new AtomicBoolean(false);
        Executor flakyHost = task -> {
            if (rejectNext.getAndSet(false)) {
                throw new java.util.concurrent.RejectedExecutionException("host gateway unavailable");
            }
            task.run();
        };
        registry = createTestRegistry(flakyHost);
        registry.whenReady().toCompletableFuture().get(5, TimeUnit.SECONDS);
        ModuleId moduleId = ModuleId.of("flaky-host-module");
        registry.registerModule(ModuleDescriptor.builder(moduleId, ModulePrimaryType.APPLICATION, TARGET).build(), null);

        rejectNext.set(true);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> registry
                .enableAsync(req(moduleId, ModuleLifecycleOperation.ENABLE, 1L, 0L)).toCompletableFuture()
                .get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);

        ModuleLifecycleResult retried = registry.enable(ModuleLifecycleRequest.request(registry.runtimeEpochId(),
                "tester", moduleId, ModuleLifecycleOperation.ENABLE, 1L, 0L, "retry-after-host-failure"));
        assertTrue(retried.succeeded(), "the failed attempt must not leave conflict_in_flight behind: " + retried.failure());
    }

    @Test
    @DisplayName("Restart reconciliation restores enabled SYSTEM children and keeps independently disabled FEATURES off")
    void restartReconciliationAppliesDurableDesiredState() throws Exception {
        ModuleId sysId = ModuleId.of("autopost-system");
        ModuleId discoveryId = ModuleId.of("autopost-discovery");
        ModuleId engagementId = ModuleId.of("autopost-engagement");
        ModuleId standaloneId = ModuleId.of("standalone-disabled");
        List<ModuleDescriptor> descriptors = List.of(
                ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build(),
                ModuleDescriptor.builder(discoveryId, ModulePrimaryType.FEATURE, TARGET).parentSystemId(sysId).build(),
                ModuleDescriptor.builder(engagementId, ModulePrimaryType.FEATURE, TARGET).parentSystemId(sysId).build(),
                ModuleDescriptor.builder(standaloneId, ModulePrimaryType.SYSTEM, TARGET).build());
        descriptors.forEach(d -> registry.registerModule(d, null));

        List<ModuleLifecycleResult> firstBoot = registry.reconcileDesiredStatesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(firstBoot.stream().allMatch(ModuleLifecycleResult::succeeded));
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(engagementId).orElseThrow().state());
        assertEquals(1L, registry.createSnapshot().configGeneration(), "reconciliation must not mutate desired state");

        ModuleSnapshot engagement = registry.findModule(engagementId).orElseThrow();
        assertTrue(registry.disable(req(engagementId, ModuleLifecycleOperation.DISABLE,
                engagement.configGeneration(), engagement.lifecycleGeneration())).succeeded());
        ModuleSnapshot standalone = registry.findModule(standaloneId).orElseThrow();
        assertTrue(registry.disable(req(standaloneId, ModuleLifecycleOperation.DISABLE,
                standalone.configGeneration(), standalone.lifecycleGeneration())).succeeded());
        registry.close();

        registry = createTestRegistry(Runnable::run);
        descriptors.forEach(d -> registry.registerModule(d, null));
        List<ModuleLifecycleResult> restart = registry.reconcileDesiredStatesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(restart.stream().allMatch(ModuleLifecycleResult::succeeded));
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(sysId).orElseThrow().state());
        assertEquals(ModuleLifecycleState.ENABLED, registry.findModule(discoveryId).orElseThrow().state());
        assertFalse(registry.findModule(engagementId).orElseThrow().isActive());
        assertEquals(ModuleDesiredState.DISABLED, registry.findModule(engagementId).orElseThrow().desiredState());
        assertFalse(registry.findModule(standaloneId).orElseThrow().isActive());

        // A second reconciliation is a no-op for active roots and keeps the disabled FEATURE off.
        registry.reconcileDesiredStatesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(registry.findModule(engagementId).orElseThrow().isActive());
    }

    // --------------------------------------------------------------------------------------------
    // Lifecycle Review Regressions (2026-10-15)
    // --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("doLoad invokes participant.onLoadAsync; failing load transitions to FAILED with module_load_failed and closes context")
    void testDoLoadInvokesOnLoadAsyncAndFailsClosed() {
        // 1. Successful load invokes onLoadAsync before transitioning to LOADED
        ModuleId successId = ModuleId.of("load-success-module");
        List<String> lifecyclePhases = Collections.synchronizedList(new ArrayList<>());
        registry.registerModule(
                ModuleDescriptor.builder(successId, ModulePrimaryType.APPLICATION, TARGET).build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onLoadAsync(IModuleContext context) {
                        lifecyclePhases.add("load");
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> onStartAsync(IModuleContext context) {
                        lifecyclePhases.add("start");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );

        ModuleLifecycleResult loadResult = registry.load(req(successId, ModuleLifecycleOperation.LOAD, 1L, 0L));
        assertTrue(loadResult.succeeded());
        assertEquals(ModuleLifecycleState.LOADED, loadResult.currentState());
        assertEquals(List.of("load"), lifecyclePhases);
        assertEquals(ModuleLifecycleState.LOADED, registry.findModule(successId).orElseThrow().state());

        // 2. Failing onLoadAsync transitions to FAILED with module_load_failed and closes context
        ModuleId failingId = ModuleId.of("load-failure-module");
        AtomicReference<IModuleContext> capturedContext = new AtomicReference<>();
        registry.registerModule(
                ModuleDescriptor.builder(failingId, ModulePrimaryType.APPLICATION, TARGET).build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onLoadAsync(IModuleContext context) {
                        capturedContext.set(context);
                        return CompletableFuture.failedFuture(new IllegalStateException("simulated storage failure"));
                    }
                }
        );

        ModuleLifecycleResult failResult = registry.load(req(failingId, ModuleLifecycleOperation.LOAD, 1L, 0L));
        assertFalse(failResult.succeeded());
        assertEquals("module_load_failed", failResult.failure().code());
        assertEquals(ModuleLifecycleState.FAILED, failResult.currentState());
        assertEquals(ModuleLifecycleState.FAILED, registry.findModule(failingId).orElseThrow().state());
        assertNotNull(capturedContext.get());
        // Verify context is closed (cannot register dependencies or allow capabilities)
        assertThrows(IllegalStateException.class, () ->
                capturedContext.get().registerDependency(String.class, "closed-test"));

        // 3. Enable on an unloaded module runs implicit load, calling onLoadAsync then onStartAsync
        ModuleId implicitId = ModuleId.of("implicit-load-module");
        List<String> implicitPhases = Collections.synchronizedList(new ArrayList<>());
        registry.registerModule(
                ModuleDescriptor.builder(implicitId, ModulePrimaryType.APPLICATION, TARGET).build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onLoadAsync(IModuleContext context) {
                        implicitPhases.add("load");
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> onStartAsync(IModuleContext context) {
                        implicitPhases.add("start");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );

        ModuleLifecycleResult enableResult = registry.enable(req(implicitId, ModuleLifecycleOperation.ENABLE, 1L, 0L));
        assertTrue(enableResult.succeeded());
        assertEquals(ModuleLifecycleState.ENABLED, enableResult.currentState());
        assertEquals(List.of("load", "start"), implicitPhases);

        // 4. Reload invokes onLoadAsync then onStartAsync for the new generation without double calling onLoadAsync
        ModuleId reloadId = ModuleId.of("reload-lifecycle-module");
        List<String> reloadPhases = Collections.synchronizedList(new ArrayList<>());
        registry.registerModule(
                ModuleDescriptor.builder(reloadId, ModulePrimaryType.APPLICATION, TARGET)
                        .reloadSupported(true)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onLoadAsync(IModuleContext context) {
                        reloadPhases.add("load");
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> onStartAsync(IModuleContext context) {
                        reloadPhases.add("start");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );
        long reloadCfg = registry.createSnapshot().configGeneration();
        assertTrue(registry.enable(req(reloadId, ModuleLifecycleOperation.ENABLE, reloadCfg, 0L)).succeeded());
        assertEquals(List.of("load", "start"), reloadPhases);

        reloadCfg = registry.createSnapshot().configGeneration();
        ModuleLifecycleResult reloadResult = registry.reload(req(reloadId, ModuleLifecycleOperation.RELOAD, reloadCfg, 1L));
        assertTrue(reloadResult.succeeded());
        assertEquals(List.of("load", "start", "load", "start"), reloadPhases);
    }

    @Test
    @DisplayName("SYSTEM child cascade failure records lastFailure on SYSTEM, keeps ENABLED state, and aggregate health reports failed children")
    void testSystemCascadeEnableFailureRecordsLastFailureAndAggregatesHealth() {
        ModuleId sysId = ModuleId.of("cascade-sys");
        ModuleId healthyChildId = ModuleId.of("cascade-healthy-child");
        ModuleId failingChildId = ModuleId.of("cascade-failing-child");

        registry.registerModule(
                ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET).build(),
                null
        );
        registry.registerModule(
                ModuleDescriptor.builder(healthyChildId, ModulePrimaryType.FEATURE, TARGET)
                        .parentSystemId(sysId)
                        .required(false)
                        .build(),
                null
        );
        registry.registerModule(
                ModuleDescriptor.builder(failingChildId, ModulePrimaryType.FEATURE, TARGET)
                        .parentSystemId(sysId)
                        .required(false)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onStartAsync(IModuleContext context) {
                        return CompletableFuture.failedFuture(new RuntimeException("payment gateway connection refused"));
                    }
                }
        );

        // Enable the SYSTEM; child cascade will fail for failingChildId
        ModuleLifecycleResult sysEnable = registry.enable(req(sysId, ModuleLifecycleOperation.ENABLE, 1L, 0L));
        assertFalse(sysEnable.succeeded());
        assertEquals("child_cascade_enable_failed", sysEnable.failure().code());

        // 1. Inspect SYSTEM entry: truthful lifecycle state is ENABLED (it did start), but lastFailure is recorded
        ModuleSnapshot sysSnapshot = registry.findModule(sysId).orElseThrow();
        assertEquals(ModuleLifecycleState.ENABLED, sysSnapshot.state(), "SYSTEM own state must stay truthful (it started)");
        assertTrue(sysSnapshot.failure().isPresent(), "SYSTEM lastFailure must be recorded on entry");
        assertEquals("child_cascade_enable_failed", sysSnapshot.lastFailure().code());
        assertEquals(ModuleHealthStatus.DEGRADED, sysSnapshot.health().status());

        // 2. Inspect child entries: healthy child is ENABLED, failing child is FAILED with UNHEALTHY status
        ModuleSnapshot healthySnapshot = registry.findModule(healthyChildId).orElseThrow();
        assertEquals(ModuleLifecycleState.ENABLED, healthySnapshot.state());

        ModuleSnapshot failingSnapshot = registry.findModule(failingChildId).orElseThrow();
        assertEquals(ModuleLifecycleState.FAILED, failingSnapshot.state());
        assertTrue(failingSnapshot.failure().isPresent());
        assertEquals(ModuleHealthStatus.UNHEALTHY, failingSnapshot.health().status());

        // 3. Aggregate health reports the failed children
        ModuleHealth systemAggregateHealth = registry.checkAggregateHealth(Optional.of(sysId));
        assertEquals(ModuleHealthStatus.DEGRADED, systemAggregateHealth.status());
        assertTrue(systemAggregateHealth.summary().contains(failingChildId.value()),
                "Aggregate health summary must report the failed child: " + systemAggregateHealth.summary());
        assertTrue(systemAggregateHealth.summary().contains("FAILED"),
                "Aggregate health summary must describe failure condition: " + systemAggregateHealth.summary());

        // Global aggregate health also reports the failed child
        ModuleHealth globalHealth = registry.checkAggregateHealth(Optional.empty());
        assertEquals(ModuleHealthStatus.DEGRADED, globalHealth.status());
        assertTrue(globalHealth.summary().contains(failingChildId.value()));
    }

    @Test
    @DisplayName("Shutdown closeAsync disables and unloads in reverse dependency order: dependents before dependencies, children before SYSTEM")
    void testShutdownClosesInReverseDependencyOrder() throws Exception {
        ModuleId sysId = ModuleId.of("order-system");
        ModuleId baseDepId = ModuleId.of("order-base-dep");
        ModuleId child1Id = ModuleId.of("order-child-1");
        ModuleId child2Id = ModuleId.of("order-child-2");
        ModuleId consumerId = ModuleId.of("order-external-consumer");

        List<String> closeOrder = Collections.synchronizedList(new ArrayList<>());

        // Register in forward or mixed order
        registry.registerModule(
                ModuleDescriptor.builder(baseDepId, ModulePrimaryType.API, TARGET).build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        closeOrder.add("base-dep");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );
        registry.registerModule(
                ModuleDescriptor.builder(sysId, ModulePrimaryType.SYSTEM, TARGET)
                        .addRequiredDependency(baseDepId)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        closeOrder.add("system");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );
        registry.registerModule(
                ModuleDescriptor.builder(child1Id, ModulePrimaryType.FEATURE, TARGET)
                        .parentSystemId(sysId)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        closeOrder.add("child-1");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );
        registry.registerModule(
                ModuleDescriptor.builder(child2Id, ModulePrimaryType.FEATURE, TARGET)
                        .parentSystemId(sysId)
                        .addRequiredDependency(child1Id)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        closeOrder.add("child-2");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );
        registry.registerModule(
                ModuleDescriptor.builder(consumerId, ModulePrimaryType.APPLICATION, TARGET)
                        .addRequiredDependency(child2Id)
                        .build(),
                () -> new IModuleLifecycleParticipant() {
                    @Override
                    public CompletionStage<Void> onCloseAsync(IModuleContext context) {
                        closeOrder.add("consumer");
                        return CompletableFuture.completedFuture(null);
                    }
                }
        );

        // Enable all modules
        long cfg1 = registry.createSnapshot().configGeneration();
        assertTrue(registry.enable(req(baseDepId, ModuleLifecycleOperation.ENABLE, cfg1, 0L)).succeeded());
        long cfg2 = registry.createSnapshot().configGeneration();
        assertTrue(registry.enable(req(sysId, ModuleLifecycleOperation.ENABLE, cfg2, 0L)).succeeded());
        long cfg3 = registry.createSnapshot().configGeneration();
        assertTrue(registry.enable(req(consumerId, ModuleLifecycleOperation.ENABLE, cfg3, 0L)).succeeded());

        // Perform shutdown
        registry.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Verify order:
        // 1. consumer (dependent) must close before child-2 (its dependency)
        // 2. child-2 (dependent) must close before child-1 (its dependency)
        // 3. child-1 and child-2 (children) must close before system (parent SYSTEM)
        // 4. system must close before base-dep (its dependency)
        int consumerIdx = closeOrder.indexOf("consumer");
        int child2Idx = closeOrder.indexOf("child-2");
        int child1Idx = closeOrder.indexOf("child-1");
        int systemIdx = closeOrder.indexOf("system");
        int baseDepIdx = closeOrder.indexOf("base-dep");

        assertTrue(consumerIdx != -1 && child2Idx != -1 && consumerIdx < child2Idx,
                "Consumer must close before child-2 (its dependency); was " + closeOrder);
        assertTrue(child2Idx < child1Idx,
                "Child-2 must close before child-1 (its dependency); was " + closeOrder);
        assertTrue(child1Idx < systemIdx && child2Idx < systemIdx,
                "Children must close before parent SYSTEM; was " + closeOrder);
        assertTrue(systemIdx < baseDepIdx,
                "System must close before base-dep (its dependency); was " + closeOrder);

        // All modules must be UNLOADED
        assertEquals(ModuleLifecycleState.UNLOADED, registry.findModule(consumerId).orElseThrow().state());
        assertEquals(ModuleLifecycleState.UNLOADED, registry.findModule(child2Id).orElseThrow().state());
        assertEquals(ModuleLifecycleState.UNLOADED, registry.findModule(child1Id).orElseThrow().state());
        assertEquals(ModuleLifecycleState.UNLOADED, registry.findModule(sysId).orElseThrow().state());
        assertEquals(ModuleLifecycleState.UNLOADED, registry.findModule(baseDepId).orElseThrow().state());
    }
}

