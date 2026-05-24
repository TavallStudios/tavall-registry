package org.tavall.registry;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Contract for a typed registry backed by a key/value map.
 */
public interface IAbstractRegistry<REG_KEY, REG_DATA> {

    IAbstractRegistry<REG_KEY, REG_DATA> createRegistry(REG_KEY registryKey, REG_DATA registryData);

    REG_KEY getRegistryKeyByData(REG_DATA registryData);

    REG_DATA getRegistryData(REG_KEY registryKey);

    Set<REG_KEY> getRegistryKeysAsSet();

    List<REG_KEY> getRegistryKeysAsList();

    Collection<REG_KEY> getRegistryKeysAsCollection();

    Set<REG_DATA> getRegistryDataAsSet();

    List<REG_DATA> getRegistryDataAsList();

    Collection<REG_DATA> getRegistryDataAsCollection();

    boolean hasRegistryKey(REG_KEY registryKey);

    boolean hasRegistryData(REG_DATA registryData);
}
