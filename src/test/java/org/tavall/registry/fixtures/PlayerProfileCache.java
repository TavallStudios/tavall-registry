package org.tavall.registry.fixtures;

import org.tavall.abstractcache.cache.AbstractCache;
import org.tavall.abstractcache.cache.interfaces.ICacheStats;
import org.tavall.abstractcache.cache.metadata.CacheEntryMetaData;
import org.tavall.abstractcache.cache.metadata.CacheRegistryMetaData;
import org.tavall.abstractcache.cache.metadata.ICacheRegistryMetaDataProvider;
import org.tavall.abstractcache.cache.metadata.MemoryPolicy;
import org.tavall.abstractcache.semantic.stats.CacheStatsProvider;
import org.tavall.abstractcache.semantic.stats.CacheStatsRegistry;
import org.tavall.dependency.metadata.DependencySource;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PlayerProfileCache
        extends AbstractCache<UUID, PlayerProfileBucket>
        implements CacheStatsProvider, ICacheRegistryMetaDataProvider {

    public static final String CACHE_NAME = "player-profile-cache";

    public PlayerProfileCache() {
        super(10, TimeUnit.MINUTES);
        CacheStatsRegistry.getInstance().register(this);
    }

    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    @Override
    public ICacheStats snapshotStats() {
        return getCacheStats();
    }

    @Override
    public Optional<CacheRegistryMetaData> createCacheRegistryMetaData() {
        CacheRegistryMetaData cacheRegistryMetaData = CacheRegistryMetaData.builder()
                .cacheClass(PlayerProfileCache.class)
                .cacheName(CACHE_NAME)
                .keyClass(UUID.class)
                .valueClass(PlayerProfile.class)
                .bucketClass(PlayerProfileBucket.class)
                .cacheEntryMetaDataClass(CacheEntryMetaData.class)
                .dependencySource(DependencySource.CACHE)
                .memoryPolicy(MemoryPolicy.HOT)
                .persistent(false)
                .distributed(false)
                .profileEnabled(true)
                .ownerModule("tavall-registry-test")
                .build();

        return Optional.of(cacheRegistryMetaData);
    }
}
