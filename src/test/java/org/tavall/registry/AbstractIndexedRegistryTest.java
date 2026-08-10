package org.tavall.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AbstractIndexedRegistryTest {

    @Test
    void replacementMovesSecondaryIndexAtomically() {
        ProbeRegistry registry = new ProbeRegistry();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        registry.registerIndexed(id, new ProbeData("alpha"));

        ProbeData previous = registry.registerIndexed(id, new ProbeData("beta"));

        assertEquals("alpha", previous.name());
        assertNull(registry.findByName("alpha"));
        assertEquals(id, registry.findByName("beta"));
        assertEquals("beta", registry.getRegistryData(id).name());
    }

    @Test
    void duplicateSecondaryOwnershipIsRejectedBeforePublication() {
        ProbeRegistry registry = new ProbeRegistry();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        registry.registerIndexed(first, new ProbeData("alpha"));

        assertThrows(
                IllegalStateException.class,
                () -> registry.registerIndexed(second, new ProbeData("alpha"))
        );

        assertFalse(registry.containsKey(second));
        assertEquals(first, registry.findByName("alpha"));
    }

    @Test
    void failedIndexPublicationRestoresPreviousPrimaryAndIndex() {
        ProbeRegistry registry = new ProbeRegistry();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        registry.registerIndexed(id, new ProbeData("alpha"));

        assertThrows(
                IllegalStateException.class,
                () -> registry.registerIndexed(id, new ProbeData("explode"))
        );

        assertEquals("alpha", registry.getRegistryData(id).name());
        assertEquals(id, registry.findByName("alpha"));
        assertNull(registry.findByName("explode"));
    }

    @Test
    void inheritedRemovalUnindexesValue() {
        ProbeRegistry registry = new ProbeRegistry();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        registry.put(id, new ProbeData("alpha"));

        ProbeData removed = registry.remove(id);

        assertEquals("alpha", removed.name());
        assertNull(registry.findByName("alpha"));
        assertFalse(registry.containsKey(id));
    }

    private record ProbeData(String name) {
    }

    private static final class ProbeRegistry
            extends AbstractIndexedRegistry<UUID, ProbeData> {
        private final Map<String, UUID> idsByName = new ConcurrentHashMap<>();

        @Override
        protected void validateRegistration(
                UUID id,
                ProbeData data,
                ProbeData previous
        ) {
            UUID owner = idsByName.get(data.name());
            if (owner != null && !owner.equals(id)) {
                throw new IllegalStateException(
                        "name is already owned: " + data.name()
                );
            }
        }

        @Override
        protected void index(UUID id, ProbeData data) {
            idsByName.put(data.name(), id);
            if (data.name().equals("explode")) {
                throw new IllegalStateException("index publication failed");
            }
        }

        @Override
        protected void unindex(UUID id, ProbeData data) {
            idsByName.remove(data.name(), id);
        }

        private UUID findByName(String name) {
            return idsByName.get(name);
        }
    }
}
