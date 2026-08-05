package fr.quentin.poppy.util.cooldown;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-player expiry tracker — every cooldown in the plugin is
 * created through {@link CooldownManager} rather than instantiating this
 * class directly.
 *
 * <p><b>Thread-safe on purpose, not just by accident:</b> {@link #expiries}
 * is a {@link ConcurrentHashMap}. Every cooldown used to live entirely on
 * the main thread, so a plain {@code HashMap} was safe — that invariant
 * broke the moment {@code ChatFormatListener} started calling into this
 * class from {@link org.bukkit.event.EventHandler} on
 * {@code AsyncChatEvent}, which dispatches on the sending player's own
 * netty thread, a different thread per player, concurrently with the
 * main thread's periodic {@link #purgeExpired()} and any {@code /cooldowns}
 * read. Multiple concurrent writers on a plain {@code HashMap} can, in
 * the worst case, corrupt its internal bucket structure during a resize
 * (a classic infinite-loop-on-get() failure mode), which on a populated
 * server would show up as a netty thread pegged at 100% CPU and a
 * watchdog crash — not hypothetical, reproducible under real concurrent
 * chat traffic. {@link ConcurrentHashMap} costs effectively nothing on
 * the other 13 cooldowns that remain main-thread-only, and removes the
 * entire bug class rather than documenting "don't call this from async."
 *
 * <p>{@link #tryStart(UUID, long)} exists because
 * {@link #remainingSeconds(UUID)} followed by {@link #start(UUID, long)}
 * is a non-atomic check-then-act: two messages sent in the same instant
 * (a macro, or simply two netty threads racing) could both pass the
 * remaining-time check before either had actually posted the cooldown,
 * silently bypassing it entirely. {@code tryStart} does the check and
 * the start as a single map operation via
 * {@link ConcurrentHashMap#compute}, safe under concurrent callers.
 * Prefer it over the separate {@code remainingSeconds}/{@code start} pair
 * for anything reachable from more than one thread.
 */
public final class CooldownStore {

    private static final long PURGE_INTERVAL_TICKS = 20L * 60 * 5; // 5 minutes

    private final Map<UUID, Long> expiries = new ConcurrentHashMap<>();

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
            // Conditional remove: only clears this exact stale value, never
            // clobbering a concurrent start() that may have just replaced it.
            expiries.remove(uuid, until);
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
     * Atomic check-and-start: if the player has no active cooldown, starts
     * a fresh one for {@code durationMillis} and returns 0. If they do,
     * leaves the existing expiry untouched and returns the seconds
     * remaining on it. Safe to call concurrently from multiple threads —
     * unlike calling {@link #remainingSeconds} then {@link #start}
     * separately, which races under concurrent callers (see the
     * class-level doc).
     */
    public long tryStart(UUID uuid, long durationMillis) {
        if (durationMillis <= 0) {
            return 0;
        }

        long now = System.currentTimeMillis();
        long[] remaining = {0L};

        expiries.compute(uuid, (_, until) -> {
            if (until != null && until > now) {
                remaining[0] = ((until - now) / 1000) + 1;
                return until;
            }
            return now + durationMillis;
        });

        return remaining[0];
    }

    /**
     * Forces this cooldown to end immediately for a specific player,
     * regardless of how much time is left.
     */
    public void clear(UUID uuid) {
        expiries.remove(uuid);
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        expiries.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}