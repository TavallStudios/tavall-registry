package org.tavall.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Registry base for values published through one primary key and one or more
 * subclass-owned secondary indexes.
 *
 * <p>Every mutation is serialized through the registry instance. Subclasses
 * validate before publication and implement idempotent index/unindex hooks so
 * a failed index publication can be rolled back without exposing partial
 * registry state.</p>
 */
public abstract class AbstractIndexedRegistry<REG_KEY, REG_DATA>
        extends AbstractRegistry<REG_KEY, REG_DATA> {

    /**
     * Validates a registration before any primary or secondary state changes.
     */
    protected void validateRegistration(
            REG_KEY registryKey,
            REG_DATA registryData,
            REG_DATA previousData
    ) {
    }

    /**
     * Publishes all secondary indexes for one already-validated value.
     */
    protected abstract void index(REG_KEY registryKey, REG_DATA registryData);

    /**
     * Removes all secondary indexes for one value. This hook must be idempotent.
     */
    protected abstract void unindex(REG_KEY registryKey, REG_DATA registryData);

    public final synchronized REG_DATA registerIndexed(
            REG_KEY registryKey,
            REG_DATA registryData
    ) {
        REG_KEY key = Objects.requireNonNull(registryKey, "registryKey");
        REG_DATA data = Objects.requireNonNull(registryData, "registryData");
        REG_DATA previous = super.get(key);
        validateRegistration(key, data, previous);

        if (previous != null) {
            unindex(key, previous);
        }
        super.put(key, data);
        try {
            index(key, data);
            return previous;
        } catch (RuntimeException | Error failure) {
            rollbackRegistration(key, data, previous, failure);
            throw failure;
        }
    }

    public final synchronized REG_DATA unregisterIndexed(REG_KEY registryKey) {
        if (registryKey == null) {
            return null;
        }
        REG_DATA existing = super.get(registryKey);
        if (existing == null) {
            return null;
        }
        unindex(registryKey, existing);
        if (!super.remove(registryKey, existing)) {
            index(registryKey, existing);
            throw new IllegalStateException(
                    "Indexed registry primary value changed during unregister: "
                            + registryKey
            );
        }
        return existing;
    }

    public final synchronized void clearIndexed() {
        List<Map.Entry<REG_KEY, REG_DATA>> snapshot = new ArrayList<>(
                super.entrySet()
        );
        List<Map.Entry<REG_KEY, REG_DATA>> unindexed = new ArrayList<>();
        try {
            for (Map.Entry<REG_KEY, REG_DATA> entry : snapshot) {
                unindex(entry.getKey(), entry.getValue());
                unindexed.add(entry);
            }
            super.clear();
        } catch (RuntimeException | Error failure) {
            for (int index = unindexed.size() - 1; index >= 0; index--) {
                Map.Entry<REG_KEY, REG_DATA> entry = unindexed.get(index);
                try {
                    index(entry.getKey(), entry.getValue());
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public final synchronized REG_DATA put(
            REG_KEY registryKey,
            REG_DATA registryData
    ) {
        return registerIndexed(registryKey, registryData);
    }

    @Override
    public final synchronized REG_DATA putIfAbsent(
            REG_KEY registryKey,
            REG_DATA registryData
    ) {
        REG_DATA existing = super.get(registryKey);
        if (existing != null) {
            return existing;
        }
        registerIndexed(registryKey, registryData);
        return null;
    }

    @Override
    public final synchronized void putAll(
            Map<? extends REG_KEY, ? extends REG_DATA> values
    ) {
        Objects.requireNonNull(values, "values");
        values.forEach(this::registerIndexed);
    }

    @Override
    public final synchronized REG_DATA remove(Object registryKey) {
        REG_DATA existing = super.get(registryKey);
        if (existing == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        REG_KEY typedKey = (REG_KEY) registryKey;
        return unregisterIndexed(typedKey);
    }

    @Override
    public final synchronized boolean remove(
            Object registryKey,
            Object registryData
    ) {
        REG_DATA existing = super.get(registryKey);
        if (!Objects.equals(existing, registryData)) {
            return false;
        }
        remove(registryKey);
        return true;
    }

    @Override
    public final synchronized REG_DATA replace(
            REG_KEY registryKey,
            REG_DATA registryData
    ) {
        if (!super.containsKey(registryKey)) {
            return null;
        }
        return registerIndexed(registryKey, registryData);
    }

    @Override
    public final synchronized boolean replace(
            REG_KEY registryKey,
            REG_DATA previousData,
            REG_DATA registryData
    ) {
        if (!Objects.equals(super.get(registryKey), previousData)) {
            return false;
        }
        registerIndexed(registryKey, registryData);
        return true;
    }

    @Override
    public final synchronized void replaceAll(
            BiFunction<? super REG_KEY, ? super REG_DATA, ? extends REG_DATA> function
    ) {
        Objects.requireNonNull(function, "function");
        for (Map.Entry<REG_KEY, REG_DATA> entry : List.copyOf(super.entrySet())) {
            REG_DATA replacement = Objects.requireNonNull(
                    function.apply(entry.getKey(), entry.getValue()),
                    "replacement"
            );
            registerIndexed(entry.getKey(), replacement);
        }
    }

    @Override
    public final synchronized REG_DATA compute(
            REG_KEY registryKey,
            BiFunction<? super REG_KEY, ? super REG_DATA, ? extends REG_DATA> function
    ) {
        Objects.requireNonNull(function, "function");
        REG_DATA replacement = function.apply(
                registryKey,
                super.get(registryKey)
        );
        if (replacement == null) {
            return unregisterIndexed(registryKey);
        }
        registerIndexed(registryKey, replacement);
        return replacement;
    }

    @Override
    public final synchronized REG_DATA computeIfAbsent(
            REG_KEY registryKey,
            Function<? super REG_KEY, ? extends REG_DATA> function
    ) {
        Objects.requireNonNull(function, "function");
        REG_DATA existing = super.get(registryKey);
        if (existing != null) {
            return existing;
        }
        REG_DATA created = function.apply(registryKey);
        if (created != null) {
            registerIndexed(registryKey, created);
        }
        return created;
    }

    @Override
    public final synchronized REG_DATA computeIfPresent(
            REG_KEY registryKey,
            BiFunction<? super REG_KEY, ? super REG_DATA, ? extends REG_DATA> function
    ) {
        Objects.requireNonNull(function, "function");
        REG_DATA existing = super.get(registryKey);
        if (existing == null) {
            return null;
        }
        REG_DATA replacement = function.apply(registryKey, existing);
        if (replacement == null) {
            unregisterIndexed(registryKey);
            return null;
        }
        registerIndexed(registryKey, replacement);
        return replacement;
    }

    @Override
    public final synchronized REG_DATA merge(
            REG_KEY registryKey,
            REG_DATA registryData,
            BiFunction<? super REG_DATA, ? super REG_DATA, ? extends REG_DATA> function
    ) {
        Objects.requireNonNull(registryData, "registryData");
        Objects.requireNonNull(function, "function");
        REG_DATA existing = super.get(registryKey);
        REG_DATA replacement = existing == null
                ? registryData
                : function.apply(existing, registryData);
        if (replacement == null) {
            unregisterIndexed(registryKey);
            return null;
        }
        registerIndexed(registryKey, replacement);
        return replacement;
    }

    @Override
    public final synchronized void clear() {
        clearIndexed();
    }

    private void rollbackRegistration(
            REG_KEY registryKey,
            REG_DATA registryData,
            REG_DATA previousData,
            Throwable failure
    ) {
        try {
            unindex(registryKey, registryData);
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        super.remove(registryKey, registryData);
        if (previousData == null) {
            return;
        }
        super.put(registryKey, previousData);
        try {
            index(registryKey, previousData);
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
