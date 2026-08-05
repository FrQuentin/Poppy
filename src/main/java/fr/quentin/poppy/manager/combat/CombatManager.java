package fr.quentin.poppy.manager.combat;

import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;

import java.util.UUID;

/**
 * Tracks per-player combat-tag expiry — backed by an actual
 * {@link CooldownStore}, obtained from the shared {@link CooldownManager}
 * like every other cooldown in the plugin, rather than a separate
 * hand-rolled {@code Map<UUID, Long>} + purge task that was structurally
 * identical to {@link CooldownStore} anyway.
 */
public class CombatManager {

    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public CombatManager(PoppyConfig config, CooldownManager cooldownManager) {
        this.config = config;
        this.cooldown = cooldownManager.get("combat", "Combat");
    }

    public void tag(UUID uuid) {
        cooldown.start(uuid, config.combatTagMillis());
    }

    public boolean isInCombat(UUID uuid) {
        return cooldown.isActive(uuid);
    }

    public long remainingSeconds(UUID uuid) {
        return cooldown.remainingSeconds(uuid);
    }

    public void remove(UUID uuid) {
        cooldown.clear(uuid);
    }
}