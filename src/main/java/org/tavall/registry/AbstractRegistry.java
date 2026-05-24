package org.tavall.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map-backed registry implementation.
 */
public abstract class AbstractRegistry<REG_KEY, REG_DATA>
        extends ConcurrentHashMap<REG_KEY, REG_DATA> implements IAbstractRegistry<REG_KEY, REG_DATA> {

    @Override
    public IAbstractRegistry<REG_KEY, REG_DATA> createRegistry(REG_KEY registryKey, REG_DATA registryData) {
        putIfAbsent(
                Objects.requireNonNull(registryKey, "registryKey"),
                Objects.requireNonNull(registryData, "registryData")
        );
        return this;
    }

    @Override
    public REG_KEY getRegistryKeyByData(REG_DATA registryData) {
        if (registryData == null) {
            return null;
        }
        return entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue(), registryData))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public REG_DATA getRegistryData(REG_KEY registryKey) {
        return get(registryKey);
    }

    @Override
    public Set<REG_KEY> getRegistryKeysAsSet() {
        return Collections.unmodifiableSet(new HashSet<>(keySet()));
    }

    @Override
    public List<REG_KEY> getRegistryKeysAsList() {
        return List.copyOf(keySet());
    }

    @Override
    public Collection<REG_KEY> getRegistryKeysAsCollection() {
        return Collections.unmodifiableCollection(new ArrayList<>(keySet()));
    }

    @Override
    public Set<REG_DATA> getRegistryDataAsSet() {
        return Collections.unmodifiableSet(new HashSet<>(values()));
    }

    @Override
    public List<REG_DATA> getRegistryDataAsList() {
        return List.copyOf(values());
    }

    @Override
    public Collection<REG_DATA> getRegistryDataAsCollection() {
        return Collections.unmodifiableCollection(new ArrayList<>(values()));
    }

    @Override
    public boolean hasRegistryKey(REG_KEY registryKey) {
        return registryKey != null && containsKey(registryKey);
    }

    @Override
    public boolean hasRegistryData(REG_DATA registryData) {
        return registryData != null && containsValue(registryData);
    }
}
