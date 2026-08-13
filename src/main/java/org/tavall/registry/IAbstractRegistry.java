package org.tavall.registry;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Contract for a typed registry that associates one value with each registry key.
 *
 * <p>Collection accessors return snapshots rather than live registry views so callers can inspect
 * registry state without gaining mutation access to the backing store.</p>
 *
 * @param <REG_KEY> type used to identify registered values
 * @param <REG_DATA> type of value stored by the registry
 */
public interface IAbstractRegistry<REG_KEY, REG_DATA> {

    /**
     * Registers a key/value pair when the key is not already present.
     *
     * <p>An existing mapping is left unchanged. The registry itself is returned so registrations
     * can be composed fluently.</p>
     *
     * @param registryKey key that identifies the value
     * @param registryData value to register
     * @return this registry
     * @throws NullPointerException if {@code registryKey} or {@code registryData} is {@code null}
     */
    IAbstractRegistry<REG_KEY, REG_DATA> createRegistry(REG_KEY registryKey, REG_DATA registryData);

    /**
     * Finds a key whose registered value is equal to the supplied value.
     *
     * <p>If multiple keys map to equal values, the selected key follows the registry's iteration
     * order and should not be treated as deterministic.</p>
     *
     * @param registryData value to locate
     * @return a matching key, or {@code null} when the value is {@code null} or not registered
     */
    REG_KEY getRegistryKeyByData(REG_DATA registryData);

    /**
     * Looks up the value currently associated with a registry key.
     *
     * @param registryKey key to resolve
     * @return the registered value, or {@code null} when no mapping exists
     */
    REG_DATA getRegistryData(REG_KEY registryKey);

    /**
     * Returns an immutable snapshot of the currently registered keys as a set.
     *
     * @return snapshot of registered keys
     */
    Set<REG_KEY> getRegistryKeysAsSet();

    /**
     * Returns an immutable snapshot of the currently registered keys as a list.
     *
     * <p>The list preserves the registry's iteration order at the time the snapshot is created;
     * callers should not assume that order is stable across snapshots.</p>
     *
     * @return snapshot of registered keys
     */
    List<REG_KEY> getRegistryKeysAsList();

    /**
     * Returns an immutable collection snapshot of the currently registered keys.
     *
     * @return snapshot of registered keys
     */
    Collection<REG_KEY> getRegistryKeysAsCollection();

    /**
     * Returns an immutable snapshot of the currently registered values as a set.
     *
     * <p>Equal values collapse according to normal set semantics even when they are registered
     * under different keys.</p>
     *
     * @return snapshot of distinct registered values
     */
    Set<REG_DATA> getRegistryDataAsSet();

    /**
     * Returns an immutable snapshot of the currently registered values as a list.
     *
     * <p>The list preserves the registry's iteration order at the time the snapshot is created;
     * callers should not assume that order is stable across snapshots.</p>
     *
     * @return snapshot of registered values
     */
    List<REG_DATA> getRegistryDataAsList();

    /**
     * Returns an immutable collection snapshot of the currently registered values.
     *
     * @return snapshot of registered values
     */
    Collection<REG_DATA> getRegistryDataAsCollection();

    /**
     * Tests whether a non-null key is currently registered.
     *
     * @param registryKey key to test
     * @return {@code true} when the key is non-null and registered
     */
    boolean hasRegistryKey(REG_KEY registryKey);

    /**
     * Tests whether a non-null value is currently registered.
     *
     * @param registryData value to test using normal equality semantics
     * @return {@code true} when an equal non-null value is registered
     */
    boolean hasRegistryData(REG_DATA registryData);
}
