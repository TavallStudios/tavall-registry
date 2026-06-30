package org.tavall.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tavall.abstractcache.cache.interfaces.ICacheRegistryAccess;
import org.tavall.abstractcache.cache.metadata.CacheRegistryMetaData;
import org.tavall.abstractcache.semantic.stats.CacheStatsRegistry;
import org.tavall.dependency.DependencyLoaderAccess;
import org.tavall.dependency.IDependencyInjectableConcrete;
import org.tavall.dependency.IDependencyInjectableInterface;
import org.tavall.dependency.injection.helpers.DependencyInjectorHelper;
import org.tavall.registry.fixtures.PlayerProfile;
import org.tavall.registry.fixtures.PlayerProfileBucket;
import org.tavall.registry.fixtures.PlayerProfileCache;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheRegistryIntegrationTest {

    @AfterEach
    void tearDown() {
        DependencyLoaderAccess.clear();
        CacheStatsRegistry.getInstance().clear();
    }

    @Test
    void playerProfileCacheAutoRegistersMetaDataAfterDiBootstrap() {
        PlayerProfileCache playerProfileCache = new PlayerProfileCache();

        DependencyInjectorHelper<IDependencyInjectableInterface, IDependencyInjectableConcrete> dependencyInjectorHelper =
                new DependencyInjectorHelper<>();
        dependencyInjectorHelper.setBasePackage("org.tavall.registry");
        dependencyInjectorHelper.setupDISystem();

        ICacheRegistryAccess cacheRegistryAccess = DependencyLoaderAccess.findInstance(ICacheRegistryAccess.class);
        assertNotNull(cacheRegistryAccess);

        UUID playerId = UUID.randomUUID();
        PlayerProfile playerProfile = new PlayerProfile(playerId);
        PlayerProfileBucket playerProfileBucket = new PlayerProfileBucket(playerProfile);
        playerProfileCache.put(playerId, playerProfileBucket);

        CacheRegistryMetaData cacheRegistryMetaData =
                cacheRegistryAccess.getCacheRegistryMetaData(PlayerProfileCache.class);

        assertNotNull(cacheRegistryMetaData);
        assertTrue(cacheRegistryAccess.hasCacheRegistryMetaData(PlayerProfileCache.class));
        assertEquals(PlayerProfileCache.class, cacheRegistryMetaData.getCacheClass());
        assertEquals(PlayerProfileCache.CACHE_NAME, cacheRegistryMetaData.getCacheName());
        assertEquals(UUID.class, cacheRegistryMetaData.getKeyClass());
        assertEquals(PlayerProfile.class, cacheRegistryMetaData.getValueClass());
        assertEquals(PlayerProfileBucket.class, cacheRegistryMetaData.getBucketClass());
        assertEquals(1, cacheRegistryAccess.getRegisteredCacheClasses().size());
        assertFalse(cacheRegistryAccess.hasCacheRegistryMetaData(UUID.class));
        assertSame(
                playerProfileBucket,
                playerProfileCache.getIfPresent(playerId, null, null, null, null)
        );
    }

    @Test
    void duplicateRegistrationIsIdempotent() {
        PlayerProfileCache firstCache = new PlayerProfileCache();

        DependencyInjectorHelper<IDependencyInjectableInterface, IDependencyInjectableConcrete> dependencyInjectorHelper =
                new DependencyInjectorHelper<>();
        dependencyInjectorHelper.setBasePackage("org.tavall.registry");
        dependencyInjectorHelper.setupDISystem();

        new PlayerProfileCache();

        ICacheRegistryAccess cacheRegistryAccess = DependencyLoaderAccess.findInstance(ICacheRegistryAccess.class);

        assertNotNull(firstCache);
        assertNotNull(cacheRegistryAccess);
        assertEquals(1, cacheRegistryAccess.getRegisteredCacheClasses().size());
    }
}
