package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
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

import java.util.UUID;
import java.util.logging.Level;

/**
 * Tags both parties in PvP (melee or projectile) into {@link CombatManager},
 * which {@link TeleportManager} (and {@link FlyManager}) then check to
 * block teleporting/flying for a short time after combat — see
 * {@code combat-tag-enabled} / {@code combat-tag-seconds} in config.yml.
 *
 * <p>Death clears the tag immediately rather than letting it linger for
 * its remaining duration: a dead player is by definition no longer in
 * combat, so leaving the tag active would incorrectly block things like
 * {@code /deathback} right when the player most needs to teleport.
 *
 * <p><b>Disconnecting during combat deliberately does NOT clear the tag</b>
 * — unlike this class's own earlier behavior, but consistent with every
 * other durable-cooldown map in this plugin. For the combat tag
 * specifically, clearing it on quit is also the classic "Alt+F4 to dodge
 * a losing fight" exploit — without this fix, disconnecting mid-fight and
 * reconnecting a few seconds later gave a player back full
 * {@code /home}/{@code /fly} access with zero consequence, and also
 * silently un-blocked {@link FlyManager}, whose lockout piggybacks on
 * {@link CombatManager#isInCombat}. {@link CombatManager}'s
 * {@code Map<UUID, Long>} is already self-expiring, so there's no
 * memory-leak concern in simply not removing the entry here.
 *
 * <p>On top of that, {@link #onQuit} actively punishes a combat-log:
 * toggleable via {@code combat-log-punish} in config.yml, a player who
 * disconnects while still tagged is killed on the spot and a server-wide
 * message announces it. {@link DeathChestManager#suppressNextDeathChest}
 * is called first, so this specific death behaves exactly like vanilla —
 * items drop on the ground, lootable by whoever the player was fighting —
 * rather than getting a protected death chest, which would let the
 * disconnecter keep their loot safe and defeat the entire point of the
 * punishment.
 */
public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final CombatManager combatManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final Messages messages;
    private final DeathChestManager deathChestManager;

    public CombatListener(JavaPlugin plugin, CombatManager combatManager, PoppyConfig config, PoppyLogger logger,
                          Messages messages, DeathChestManager deathChestManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.config = config;
        this.logger = logger;
        this.messages = messages;
        this.deathChestManager = deathChestManager;
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
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!config.combatLogPunishEnabled() || !combatManager.isInCombat(uuid)) {
            return;
        }

        try {
            logger.log(PoppyLogger.Category.COMBAT, player, "disconnected while in combat (combat log)");
            Bukkit.getServer().sendMessage(messages.get("combat.log-punished", "player", player.getName()));
            deathChestManager.suppressNextDeathChest(uuid);
            player.setHealth(0.0);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error punishing a combat log for " + player.getName(), e);
        }
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