package org.tavall.registry.module.operator;

import org.tavall.registry.module.CapabilityId;
import org.tavall.registry.module.IModuleLifecycleRegistry;
import org.tavall.registry.module.ModuleDescriptor;
import org.tavall.registry.module.ModuleFailure;
import org.tavall.registry.module.ModuleId;
import org.tavall.registry.module.ModuleLifecycleOperation;
import org.tavall.registry.module.ModuleLifecycleRequest;
import org.tavall.registry.module.ModuleLifecycleResult;
import org.tavall.registry.module.ModulePrimaryType;
import org.tavall.registry.module.ModuleSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link IModuleOperatorService} over one runtime's {@link IModuleLifecycleRegistry}, limited to the modules
 * an operator surface is allowed to see and change.
 * <p>
 * The service adds only operator concerns: views, impact previews and confirmation of destructive operations.
 * Authorization, generation fences, dependency refusal and cascades stay with the registry. Each runtime
 * composes it explicitly at its composition boundary with the registry and scope it owns, so it is not a
 * classpath-discovered DI component.
 */
public final class ModuleOperatorService implements IModuleOperatorService {

    private static final Set<ModuleLifecycleOperation> DESTRUCTIVE = Set.of(
            ModuleLifecycleOperation.DISABLE, ModuleLifecycleOperation.UNLOAD,
            ModuleLifecycleOperation.RELOAD, ModuleLifecycleOperation.ROLLBACK);

    private final IModuleLifecycleRegistry registry;
    private final Predicate<ModuleId> scope;

    /**
     * Creates the service.
     *
     * @param registry runtime lifecycle registry; must not be null
     * @param scope    modules this operator surface may see and change; must not be null
     */
    public ModuleOperatorService(IModuleLifecycleRegistry registry, Predicate<ModuleId> scope) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public List<ModuleOperatorView> list() {
        List<ModuleSnapshot> modules = registry.listModules();
        return modules.stream()
                .filter(module -> scope.test(module.id()))
                .sorted(Comparator.comparing((ModuleSnapshot module) -> module.descriptor().parentSystemId()
                                .map(ModuleId::value).orElse(module.id().value()))
                        .thenComparing(module -> module.descriptor().parentSystemId().isPresent())
                        .thenComparing(module -> module.id().value()))
                .map(module -> view(module, modules))
                .toList();
    }

    @Override
    public Optional<ModuleOperatorView> inspect(String moduleId) {
        return find(moduleId).map(module -> view(module, registry.listModules()));
    }

    @Override
    public ModuleOperationImpact impact(String moduleId, ModuleLifecycleOperation operation) {
        Objects.requireNonNull(operation, "operation");
        ModuleSnapshot module = find(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + moduleId));
        List<String> affected = new ArrayList<>();
        affected.add(module.id().value());
        if (module.descriptor().primaryType() == ModulePrimaryType.SYSTEM && DESTRUCTIVE.contains(operation)) {
            registry.findByParentSystem(module.id()).stream()
                    .filter(ModuleSnapshot::isActive)
                    .map(child -> child.id().value())
                    .sorted()
                    .forEach(affected::add);
        }
        boolean destructive = DESTRUCTIVE.contains(operation);
        String token = destructive
                ? operation.name().toLowerCase(Locale.ROOT) + ":" + module.id().value() + "@" + module.configGeneration()
                : null;
        return new ModuleOperationImpact(module.id().value(), operation, destructive, affected, token);
    }

    @Override
    public ModuleOperationOutcome execute(String actor, ModuleOperationRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        ModuleSnapshot module = find(request.moduleId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown module: " + request.moduleId()));
        ModuleOperationImpact impact = impact(request.moduleId(), request.operation());
        if (impact.destructive() && !Objects.equals(impact.confirmationToken(), request.confirmation())) {
            return new ModuleOperationOutcome(module.id().value(), request.operation(), false,
                    module.state().name(), module.desiredState().name(), module.lifecycleGeneration(),
                    module.configGeneration(), "confirmation_required",
                    "Confirm " + request.operation() + " of " + impact.affectedModules() + " with " + impact.confirmationToken(),
                    impact);
        }
        ModuleLifecycleRequest lifecycleRequest = ModuleLifecycleRequest.request(registry.runtimeEpochId(), actor,
                module.id(), request.operation(), request.expectedConfigGeneration(), request.expectedLifecycleGeneration());
        ModuleLifecycleResult result = switch (request.operation()) {
            case LOAD -> registry.load(lifecycleRequest);
            case ENABLE -> registry.enable(lifecycleRequest);
            case DISABLE -> registry.disable(lifecycleRequest);
            case RELOAD -> registry.reload(lifecycleRequest);
            case UNLOAD -> registry.unload(lifecycleRequest);
            case ROLLBACK -> registry.rollback(lifecycleRequest);
        };
        ModuleFailure failure = result.failure();
        return new ModuleOperationOutcome(module.id().value(), request.operation(), result.succeeded(),
                name(result.currentState()), name(result.desiredState()), result.lifecycleGeneration(),
                result.configGeneration(), failure == null ? null : failure.code(),
                failure == null ? null : failure.message(), impact);
    }

    private Optional<ModuleSnapshot> find(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return Optional.empty();
        }
        ModuleId id = ModuleId.of(moduleId);
        return scope.test(id) ? registry.findModule(id) : Optional.empty();
    }

    private ModuleOperatorView view(ModuleSnapshot module, List<ModuleSnapshot> all) {
        ModuleDescriptor descriptor = module.descriptor();
        List<String> dependents = all.stream()
                .filter(other -> other.descriptor().requiredDependencies().contains(module.id()))
                .map(other -> other.id().value()).sorted().toList();
        List<String> children = all.stream()
                .filter(other -> other.descriptor().parentSystemId().filter(module.id()::equals).isPresent())
                .map(other -> other.id().value()).sorted().toList();
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        descriptor.providedCapabilities().stream().sorted()
                .forEach(capability -> capabilities.put(capability.value(), available(capability)));
        return new ModuleOperatorView(
                module.id().value(), descriptor.displayName(), descriptor.version(), descriptor.primaryType().name(),
                descriptor.secondaryRoles().stream().map(Enum::name).sorted().toList(),
                descriptor.parentSystemId().map(ModuleId::value).orElse(null),
                module.state().name(), module.desiredState().name(),
                module.lifecycleGeneration(), module.configGeneration(),
                descriptor.reloadSupported(), descriptor.rollbackSupported(), descriptor.required(),
                descriptor.requiredDependencies().stream().map(ModuleId::value).sorted().toList(),
                dependents, children, capabilities,
                module.failure().map(failure -> failure.code() + ": " + failure.message()).orElse(null));
    }

    private boolean available(CapabilityId capability) {
        return registry.isReady() && registry.isCapabilityAvailable(capability);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
