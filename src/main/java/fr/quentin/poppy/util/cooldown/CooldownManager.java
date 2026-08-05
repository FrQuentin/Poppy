package fr.quentin.poppy.util.cooldown;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Single factory and lookup point for every {@link CooldownStore} in the
 * plugin — every cooldown, whether shown in {@code /cooldowns} or purely
 * internal (a brute-force lockout, a request-spam guard), is created
 * through this class rather than each command/manager calling
 * {@code new CooldownStore(plugin)} on its own.
 *
 * <p>Two entry points: {@link #get(String)} for an internal-only
 * cooldown never surfaced to players, and {@link #get(String, String)}
 * for one that should also appear in {@code /cooldowns} under the given
 * display name — registered with {@link CooldownRegistry} automatically,
 * exactly once, the first time it's created. Either way, the same
 * {@code id} always returns the same {@link CooldownStore} instance, so
 * a feature split across multiple classes (e.g. /msg and /reply sharing
 * one cooldown) just calls {@code get(...)} with the same id from both
 * places.
 */
public final class CooldownManager {

    private final JavaPlugin plugin;
    private final CooldownRegistry registry;
    private final Map<String, CooldownStore> stores = new HashMap<>();

    public CooldownManager(JavaPlugin plugin, CooldownRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * An internal-only cooldown — never listed in {@code /cooldowns}.
     */
    public CooldownStore get(String id) {
        return stores.computeIfAbsent(id, _ -> new CooldownStore(plugin));
    }

    /**
     * A player-facing cooldown, also registered with {@link CooldownRegistry}
     * under {@code displayName} the first time this {@code id} is created.
     */
    public CooldownStore get(String id, String displayName) {
        return stores.computeIfAbsent(id, _ -> {
            CooldownStore store = new CooldownStore(plugin);
            registry.register(displayName, store);
            return store;
        });
    }
}