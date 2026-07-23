package fr.quentin.poppy.manager;

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

/**
 * Tags both parties in PvP (melee or projectile) into {@link CombatManager},
 * which {@link TeleportManager} then checks to block /home, /spawn, /back
 * and /rtp for a short time after combat — see {@code combat-tag-enabled}
 * / {@code combat-tag-seconds} in config.yml.
 *
 * <p>Death clears the tag immediately rather than letting it linger for
 * its remaining duration: a dead player is by definition no longer in
 * combat, so leaving the tag active would incorrectly block things like
 * {@code /deathback} right when the player most needs to teleport.
 */
public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final CombatManager combatManager;
    private final PoppyLogger logger;
    private final boolean enabled;

    public CombatListener(JavaPlugin plugin, CombatManager combatManager, PoppyLogger logger) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.logger = logger;
        this.enabled = plugin.getConfig().getBoolean("combat-tag-enabled", true);
    }

    @EventHandler
    public void onDamage(@NonNull EntityDamageByEntityEvent event) {
        if (!enabled) {
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

            // Only log the moment combat actually starts (neither was already
            // tagged), not every single hit exchanged during an ongoing fight —
            // otherwise this would spam the log file for every swing.
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