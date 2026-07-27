package fr.quentin.poppy.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared per-player expiry tracker, replacing what used to be six separate
 * {@code Map<UUID, Long>} + a hand-rolled {@code cooldownRemaining(...)}
 * method duplicated identically across {@code FeedCommand},
 * {@code HealCommand}, {@code RtpCommand}, {@code PoppyGotoCommand},
 * {@code PoppyLoreListener}, and {@code FlyManager}.
 *
 * <p>Deliberately not cleared on quit by any of its callers — the whole
 * point of every one of those cooldowns is to survive a
 * disconnect/reconnect, otherwise a player resets it for free. What none
 * of the original six maps did, though, was ever purge an entry once it
 * had actually expired: {@link #remainingSeconds} only removes an entry
 * when it happens to be read again after expiring, so a player who used a
 * command once and never again keeps a dead entry in memory forever. On a
 * server with tens of thousands of unique players over months, that's
 * real (if small — each entry is a UUID and a long) memory that never
 * gets reclaimed. {@link #purgeExpired()} sweeps out anything already
 * expired regardless of whether it was ever re-read, and is scheduled to
 * run automatically every 5 minutes by this class's own constructor — no
 * per-caller boilerplate needed.
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

    /**
     * Seconds remaining, rounded up so a caller never shows "0s left" while
     * still technically active. Also lazily purges this specific entry if
     * it turns out to already be expired.
     */
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

    /**
     * Starts (or restarts) the cooldown. A non-positive duration is a
     * no-op — matches every caller's existing "0 or less means disabled"
     * convention, so cooldown-disabled config values never even create an
     * entry.
     */
    public void start(UUID uuid, long durationMillis) {
        if (durationMillis > 0) {
            expiries.put(uuid, System.currentTimeMillis() + durationMillis);
        }
    }

    /**
     * Removes every entry that has already expired, regardless of whether
     * it was ever read again — the sweep that {@link #remainingSeconds}'s
     * lazy purge alone can't guarantee.
     */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        expiries.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}