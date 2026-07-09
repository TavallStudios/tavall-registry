package org.tavall.registry;

import org.tavall.abstractcache.cache.interfaces.ICacheRegistryAccess;
import org.tavall.abstractcache.cache.metadata.CacheRegistryMetaData;
import org.tavall.abstractcache.semantic.stats.CacheStatsRegistry;
import org.tavall.dependency.IDependencyInjectableConcrete;
import org.tavall.dependency.annotations.DelegatesToInterface;

import java.util.Set;

@DelegatesToInterface(getLinkedInterface = ICacheRegistryAccess.class)
public final class CacheRegistry
        extends AbstractRegistry<Class<?>, CacheRegistryMetaData>
        implements ICacheRegistryAccess, IDependencyInjectableConcrete {

    public CacheRegistry() {
        CacheStatsRegistry.getInstance().flushPendingCacheRegistryMetaData(this);
    }

    @Override
    public ICacheRegistryAccess registerCacheIfAbsent(CacheRegistryMetaData cacheRegistryMetaData) {
        if (cacheRegistryMetaData == null) {
            return this;
        }

        Class<?> cacheClass = cacheRegistryMetaData.getCacheClass();
        if (cacheClass == null) {
            return this;
        }

        createRegistry(cacheClass, cacheRegistryMetaData);
        return this;
    }

    @Override
    public CacheRegistryMetaData getCacheRegistryMetaData(Class<?> cacheClass) {
        return getRegistryData(cacheClass);
    }

    @Override
    public boolean hasCacheRegistryMetaData(Class<?> cacheClass) {
        return hasRegistryKey(cacheClass);
    }

    @Override
    public Set<Class<?>> getRegisteredCacheClasses() {
        return getRegistryKeysAsSet();
    }
}
