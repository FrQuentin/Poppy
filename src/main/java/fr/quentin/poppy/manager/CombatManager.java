package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.CooldownRegistry;
import fr.quentin.poppy.util.PoppyConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks combat-tag expiry times. Duration is read live from
 * {@link PoppyConfig} at tag time.
 */
public class CombatManager {

    private final PoppyConfig config;
    private final Map<UUID, Long> combatEndTimes = new HashMap<>();

    public CombatManager(PoppyConfig config, CooldownRegistry registry) {
        this.config = config;
        registry.registerCustom("Combat", this::remainingSeconds);
    }

    public void tag(UUID uuid) {
        long durationMillis = config.combatTagMillis();
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