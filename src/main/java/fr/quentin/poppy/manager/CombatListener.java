package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final CombatManager combatManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    public CombatListener(JavaPlugin plugin, CombatManager combatManager, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.config = config;
        this.logger = logger;
    }

    @EventHandler
    public void onDamage(@NonNull EntityDamageByEntityEvent event) {
        if (!config.combatTagEnabled()) {
            return;
        }

        try {
            if (!(event.getEntity() instanceof Player victim)) {
                return;
            }

            Player attacker = resolveAttacker(event.getDamager());
            if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
                return;
            }

            boolean victimAlreadyTagged = combatManager.isInCombat(victim.getUniqueId());
            boolean attackerAlreadyTagged = combatManager.isInCombat(attacker.getUniqueId());

            combatManager.tag(victim.getUniqueId());
            combatManager.tag(attacker.getUniqueId());

            if (!victimAlreadyTagged && !attackerAlreadyTagged) {
                logger.log(PoppyLogger.Category.COMBAT, attacker, "entered combat with " + victim.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in CombatListener#onDamage", e);
        }
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        combatManager.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        combatManager.remove(event.getPlayer().getUniqueId());
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}