package fr.quentin.poppy.util.cooldown;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared per-player expiry tracker — every cooldown in the plugin is
 * created through {@link CooldownManager} rather than instantiating this
 * class directly, so there's a single place that owns cooldown creation
 * and (where relevant) {@code /cooldowns} registration.
 *
 * <p>{@link #start} is a no-op for a non-positive duration. Purged
 * lazily on read ({@link #remainingSeconds}) and periodically every 5
 * minutes ({@link #purgeExpired}) so an entry that's never read again
 * after expiring doesn't linger forever.
 */
public final class CooldownStore {

    private static final long PURGE_INTERVAL_TICKS = 20L * 60 * 5; // 5 minutes

    private final Map<UUID, Long> expiries = new HashMap<>();

    public CooldownStore(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeExpired, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    public boolean isActive(UUID uuid) {
        return remainingSeconds(uuid) > 0;
    }

    public long remainingSeconds(UUID uuid) {
        Long until = expiries.get(uuid);
        if (until == null) {
            return 0;
        }

        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            expiries.remove(uuid);
            return 0;
        }

        return (remaining / 1000) + 1;
    }

    public void start(UUID uuid, long durationMillis) {
        if (durationMillis > 0) {
            expiries.put(uuid, System.currentTimeMillis() + durationMillis);
        }
    }

    /**
     * Forces this cooldown to end immediately for a specific player,
     * regardless of how much time is left — used where an event should
     * cancel an in-progress cooldown outright (e.g. a combat tag ending
     * the moment a player dies) rather than waiting for it to expire
     * naturally.
     */
    public void clear(UUID uuid) {
        expiries.remove(uuid);
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        expiries.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}