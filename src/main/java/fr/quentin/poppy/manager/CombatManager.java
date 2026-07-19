package fr.quentin.poppy.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CombatManager {

    private final long durationMillis;
    private final Map<UUID, Long> combatEndTimes = new HashMap<>();

    public CombatManager(long durationSeconds) {
        this.durationMillis = Math.max(0, durationSeconds) * 1000L;
    }

    public void tag(UUID uuid) {
        if (durationMillis <= 0) {
            return;
        }
        combatEndTimes.put(uuid, System.currentTimeMillis() + durationMillis);
    }

    public boolean isInCombat(UUID uuid) {
        Long end = combatEndTimes.get(uuid);
        if (end == null) {
            return false;
        }
        if (System.currentTimeMillis() >= end) {
            combatEndTimes.remove(uuid);
            return false;
        }
        return true;
    }

    public long remainingSeconds(UUID uuid) {
        Long end = combatEndTimes.get(uuid);
        if (end == null) {
            return 0;
        }
        long remaining = end - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining / 1000) + 1;
    }

    public void remove(UUID uuid) {
        combatEndTimes.remove(uuid);
    }
}