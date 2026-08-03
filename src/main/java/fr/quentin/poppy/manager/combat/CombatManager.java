package fr.quentin.poppy.manager.combat;

import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.cooldown.CooldownRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-player combat-tag expiry — structurally identical to
 * {@link fr.quentin.poppy.util.cooldown.CooldownStore} (a {@code Map<UUID, Long>}
 * of expiry timestamps, with lazy purge-on-read), for the same reason:
 * the combat tag deliberately survives a disconnect (anti Alt+F4), so
 * nothing removes an entry on quit.
 *
 * <p>{@link #purgeExpired()}, run every 5 minutes, closes the gap
 * {@code CooldownStore} was given from the start but this class wasn't:
 * an entry that's never read again after expiring — a player with
 * {@code combat-log-punish} disabled, a server shutdown while players
 * were tagged, or (the default configuration) a kicked player whose
 * combat-log punishment is skipped via {@code combat-log-punish-on-kick: false}
 * — otherwise lingers in {@link #combatEndTimes} forever. Not a
 * server-crashing leak on its own, but an unbounded map on a plugin
 * meant to run for weeks without a restart, and a structural
 * inconsistency with the very pattern this class was modeled on.
 */
public class CombatManager {

    private static final long PURGE_INTERVAL_TICKS = 20L * 60 * 5; // 5 minutes

    private final PoppyConfig config;
    private final Map<UUID, Long> combatEndTimes = new HashMap<>();

    public CombatManager(JavaPlugin plugin, PoppyConfig config, CooldownRegistry registry) {
        this.config = config;
        registry.registerCustom("Combat", this::remainingSeconds);
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeExpired, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    public void tag(UUID uuid) {
        combatEndTimes.put(uuid, System.currentTimeMillis() + config.combatTagMillis());
    }

    public boolean isInCombat(UUID uuid) {
        return remainingSeconds(uuid) > 0;
    }

    public long remainingSeconds(UUID uuid) {
        Long until = combatEndTimes.get(uuid);
        if (until == null) {
            return 0;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            combatEndTimes.remove(uuid);
            return 0;
        }
        return (remaining / 1000) + 1;
    }

    public void remove(UUID uuid) {
        combatEndTimes.remove(uuid);
    }

    /**
     * Removes every entry that has already expired, regardless of
     * whether it was ever read again — same reasoning as
     * {@code CooldownStore#purgeExpired}.
     */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        combatEndTimes.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}