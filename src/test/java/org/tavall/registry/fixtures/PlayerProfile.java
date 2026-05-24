package org.tavall.registry.fixtures;

import java.util.UUID;

public final class PlayerProfile {

    private final UUID playerId;

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
