package fr.quentin.poppy.manager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AfkManager {

    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();

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

    public void clearAfk(UUID uuid) {
        afkPlayers.remove(uuid);
    }

    public void recordActivity(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
    }

    public long millisSinceActivity(UUID uuid) {
        Long last = lastActivity.get(uuid);
        if (last == null) {
            return 0;
        }
        return System.currentTimeMillis() - last;
    }

    public void remove(UUID uuid) {
        afkPlayers.remove(uuid);
        lastActivity.remove(uuid);
    }
}