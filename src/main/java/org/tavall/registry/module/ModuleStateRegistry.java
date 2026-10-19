package org.tavall.registry.module;

import org.tavall.registry.AbstractIndexedRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package-private internal indexed storage registry for {@link ModuleEntry} instances.
 * <p>
 * <b>Architectural Role:</b> Extends {@link AbstractIndexedRegistry} to manage primary key indexing
 * and secondary indices for parent systems, primary types, and capabilities.
 * <p>
 * <b>Encapsulation:</b> Completely isolated from the public lifecycle coordinator API. Callers
 * interact exclusively with immutable {@link ModuleSnapshot} projections.
 */
final class ModuleStateRegistry extends AbstractIndexedRegistry<ModuleId, ModuleEntry> {

    private final RuntimeTarget runtimeTarget;

    private final Map<ModuleId, Set<ModuleId>> parentToChildrenIndex = new ConcurrentHashMap<>();
    private final Map<ModulePrimaryType, Set<ModuleId>> primaryTypeIndex = new ConcurrentHashMap<>();
    private final Map<CapabilityId, Set<ModuleId>> capabilityIndex = new ConcurrentHashMap<>();

    ModuleStateRegistry(RuntimeTarget runtimeTarget) {
        this.runtimeTarget = Objects.requireNonNull(runtimeTarget, "runtimeTarget must not be null");
    }

    RuntimeTarget runtimeTarget() {
        return runtimeTarget;
    }

    @Override
    protected void validateRegistration(
            ModuleId registryKey,
            ModuleEntry registryData,
            ModuleEntry previousData
    ) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(registryData, "registryData");

        ModuleDescriptor descriptor = registryData.descriptor();
        if (!registryKey.equals(descriptor.id())) {
            throw new IllegalArgumentException("Registry key " + registryKey + " does not match descriptor id " + descriptor.id());
        }

        // Validate target runtime compatibility
        if (!runtimeTarget.equals(descriptor.runtimeTarget())) {
            throw new IllegalArgumentException(
                    "Cannot register module " + descriptor.id() + " targeting " + descriptor.runtimeTarget()
                            + " into registry targeting " + runtimeTarget
            );
        }

        // Validate parent system structure and cross-runtime graph
        if (descriptor.primaryType() == ModulePrimaryType.FEATURE) {
            ModuleId parentId = descriptor.parentSystemId().orElseThrow(() ->
                    new IllegalArgumentException("Module " + descriptor.id() + " of type FEATURE must declare a parentSystemId")
            );
            ModuleEntry parentEntry = get(parentId);
            if (parentEntry != null) {
                if (parentEntry.descriptor().primaryType() != ModulePrimaryType.SYSTEM) {
                    throw new IllegalArgumentException(
                            "Module " + descriptor.id() + " declares parent " + parentId
                                    + " which is of type " + parentEntry.descriptor().primaryType() + ", but parent must be SYSTEM"
                    );
                }
                if (!runtimeTarget.equals(parentEntry.descriptor().runtimeTarget())) {
                    throw new IllegalArgumentException(
                            "Module " + descriptor.id() + " cannot reference parent " + parentId
                                    + " across runtime target boundary (" + parentEntry.descriptor().runtimeTarget() + " != " + runtimeTarget + ")"
                    );
                }
            }
        } else if (descriptor.primaryType() == ModulePrimaryType.SYSTEM && descriptor.parentSystemId().isPresent()) {
            throw new IllegalArgumentException("Module " + descriptor.id() + " of type SYSTEM must not declare a parentSystemId");
        }

        // Prevent replacing an active or loaded module
        if (previousData != null && (previousData.state().isActive() || previousData.state().isLoaded())) {
            throw new IllegalStateException(
                    "Cannot re-register or replace active or loaded module " + registryKey
                            + " in state: " + previousData.state()
            );
        }
    }

    @Override
    protected void index(ModuleId registryKey, ModuleEntry registryData) {
        ModuleDescriptor descriptor = registryData.descriptor();

        // Index by parent system
        descriptor.parentSystemId().ifPresent(parentId ->
                parentToChildrenIndex.computeIfAbsent(parentId, k -> ConcurrentHashMap.newKeySet()).add(registryKey)
        );

        // Index by primary type
        primaryTypeIndex.computeIfAbsent(descriptor.primaryType(), k -> ConcurrentHashMap.newKeySet()).add(registryKey);

        // Index by provided capabilities
        for (CapabilityId capabilityId : descriptor.providedCapabilities()) {
            capabilityIndex.computeIfAbsent(capabilityId, k -> ConcurrentHashMap.newKeySet()).add(registryKey);
        }
    }

    @Override
    protected void unindex(ModuleId registryKey, ModuleEntry registryData) {
        ModuleDescriptor descriptor = registryData.descriptor();

        // Unindex parent system
        descriptor.parentSystemId().ifPresent(parentId -> {
            Set<ModuleId> children = parentToChildrenIndex.get(parentId);
            if (children != null) {
                children.remove(registryKey);
                if (children.isEmpty()) {
                    parentToChildrenIndex.remove(parentId);
                }
            }
        });

        // Unindex primary type
        Set<ModuleId> typed = primaryTypeIndex.get(descriptor.primaryType());
        if (typed != null) {
            typed.remove(registryKey);
            if (typed.isEmpty()) {
                primaryTypeIndex.remove(descriptor.primaryType());
            }
        }

        // Unindex capabilities
        for (CapabilityId capabilityId : descriptor.providedCapabilities()) {
            Set<ModuleId> providing = capabilityIndex.get(capabilityId);
            if (providing != null) {
                providing.remove(registryKey);
                if (providing.isEmpty()) {
                    capabilityIndex.remove(capabilityId);
                }
            }
        }
    }

    synchronized List<ModuleEntry> findByParentSystem(ModuleId systemId) {
        Set<ModuleId> childIds = parentToChildrenIndex.get(systemId);
        if (childIds == null || childIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModuleEntry> result = new ArrayList<>(childIds.size());
        for (ModuleId id : childIds) {
            ModuleEntry entry = get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    synchronized List<ModuleEntry> findByPrimaryType(ModulePrimaryType type) {
        Set<ModuleId> ids = primaryTypeIndex.get(type);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModuleEntry> result = new ArrayList<>(ids.size());
        for (ModuleId id : ids) {
            ModuleEntry entry = get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    synchronized List<ModuleEntry> findByCapability(CapabilityId capabilityId) {
        Set<ModuleId> ids = capabilityIndex.get(capabilityId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModuleEntry> result = new ArrayList<>(ids.size());
        for (ModuleId id : ids) {
            ModuleEntry entry = get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    synchronized Optional<ModuleSnapshot> snapshotOf(ModuleId id) {
        ModuleEntry entry = get(id);
        return entry == null ? Optional.empty() : Optional.of(toSnapshot(entry));
    }

    synchronized List<ModuleSnapshot> allSnapshots() {
        List<ModuleSnapshot> list = new ArrayList<>(size());
        for (ModuleEntry entry : values()) {
            list.add(toSnapshot(entry));
        }
        return list;
    }

    synchronized Map<ModuleId, ModuleDescriptor> descriptors() {
        Map<ModuleId, ModuleDescriptor> map = new HashMap<>(size());
        for (ModuleEntry entry : values()) {
            map.put(entry.descriptor().id(), entry.descriptor());
        }
        return map;
    }

    static ModuleSnapshot toSnapshot(ModuleEntry entry) {
        return new ModuleSnapshot(
                entry.descriptor().id(),
                entry.descriptor(),
                entry.state(),
                entry.desiredState(),
                entry.lifecycleGeneration(),
                entry.configGeneration(),
                entry.activeParticipant() != null ? entry.activeParticipant().residualObligationPolicy() : ResidualObligationPolicy.none(),
                entry.assessHealth(),
                entry.lastFailure()
        );
    }
}
