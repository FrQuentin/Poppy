package fr.quentin.poppy.manager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AfkManager {

    private final Set<UUID> afkPlayers = new HashSet<>();

    public boolean toggle(UUID uuid) {
        if (afkPlayers.contains(uuid)) {
            afkPlayers.remove(uuid);
            return false;
        }
        afkPlayers.add(uuid);
        return true;
    }

    public boolean isAfk(UUID uuid) {
        return afkPlayers.contains(uuid);
    }

    public void remove(UUID uuid) {
        afkPlayers.remove(uuid);
    }
}