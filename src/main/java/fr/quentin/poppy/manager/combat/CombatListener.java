package fr.quentin.poppy.manager.combat;

import fr.quentin.poppy.manager.fly.FlyManager;
import fr.quentin.poppy.manager.teleport.TeleportManager;
import fr.quentin.poppy.manager.back.BackManager;
import fr.quentin.poppy.manager.death.DeathLocationManager;
import fr.quentin.poppy.manager.deathchest.DeathChestManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tags both parties in PvP (melee or projectile) into {@link CombatManager},
 * which {@link TeleportManager} (and {@link FlyManager}) then check to
 * block teleporting/flying for a short time after combat.
 *
 * <p>Death clears the tag immediately. Disconnecting during combat
 * deliberately does NOT clear the tag — clearing it on quit is the
 * classic "Alt+F4 to dodge a losing fight" exploit.
 *
 * <p><b>Combat-log punishment ({@link #onQuit}):</b> toggleable via
 * {@code combat-log-punish} in config.yml, a player who disconnects while
 * still tagged has their inventory dropped and cleared explicitly, before
 * {@link Player#setHealth(double)} — kept only so they see the death
 * screen on return; the inventory is already empty by then. Excludes a
 * server shutdown ({@link Bukkit#isStopping()}) and, by default, a kick
 * ({@link #recentlyKicked}, populated by {@link #onKick} and consumed by
 * {@link #onQuit} rather than relying on a fixed-delay cleanup, closing a
 * timing race an earlier version had) — {@code combat-log-punish-on-kick}
 * opts back into punishing kicks too.
 *
 * <p>{@code setHealth(0)} still triggers a real, synchronous
 * {@link PlayerDeathEvent} nested inside the {@code PlayerQuitEvent}
 * dispatch already in progress. This class runs {@link #onQuit} at
 * {@link EventPriority#MONITOR} — deliberately last among the
 * {@code PlayerQuitEvent} listeners — so every other {@code onQuit}
 * handler that would normally clean up per-player state for this
 * disconnecting player has already run <i>before</i> the nested death
 * fires. That nested death still re-triggers every {@code PlayerDeathEvent}
 * listener, which re-inserts state those already-run {@code onQuit}
 * handlers just cleared — for a player who's now offline and will never
 * fire another {@code PlayerQuitEvent} to clean it up again. Two such
 * leaks are known and explicitly undone here, right after
 * {@code setHealth(0)}: {@link BackManager#remove(UUID)} (from
 * {@code BackListener#onDeath} recording a fresh {@code /back} location)
 * and {@link DeathLocationManager#remove(UUID)} (from
 * {@code DeathCoordsListener#onDeath} recording a fresh death location).
 * Both are undone explicitly rather than relying on listener registration
 * order, so this doesn't silently regress if either of those managers
 * changes.
 */
public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final CombatManager combatManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final Messages messages;
    private final DeathChestManager deathChestManager;
    private final BackManager backManager;
    private final DeathLocationManager deathLocationManager;

    private final Set<UUID> recentlyKicked = new HashSet<>();

    public CombatListener(JavaPlugin plugin, CombatManager combatManager, PoppyConfig config, PoppyLogger logger,
                          Messages messages, DeathChestManager deathChestManager, BackManager backManager,
                          DeathLocationManager deathLocationManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.config = config;
        this.logger = logger;
        this.messages = messages;
        this.deathChestManager = deathChestManager;
        this.backManager = backManager;
        this.deathLocationManager = deathLocationManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NonNull EntityDamageByEntityEvent event) {
        if (!config.combatTagEnabled()) {
            return;
        }

        try {
            // A hit cancelled by a protection plugin, or one that deals zero final
            // damage (a snowball, an egg, damage fully absorbed), is not combat —
            // without this guard, hitting someone in a PvP-off zone still tagged
            // them, and a disconnect within the next 10s triggered the combat-log
            // punishment (drop + kill) on a player who never actually fought. Same
            // guard as FlyManager#onDamage/onPvpDamage on the same events.
            // ignoreCancelled = true already filters out an event cancelled before
            // MONITOR runs; the explicit getFinalDamage() check on top of that also
            // catches an event some other plugin left uncancelled but reduced to
            // zero damage (e.g. full damage absorption via an effect), which
            // ignoreCancelled alone wouldn't filter.
            if (event.getFinalDamage() <= 0) {
                return;
            }

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

    /**
     * Populates {@link #recentlyKicked} — consumed directly by
     * {@link #onQuit} as soon as it fires; the scheduled removal here is
     * only a safety net in case a {@link PlayerQuitEvent} never follows
     * this kick at all.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(@NonNull PlayerKickEvent event) {
        try {
            UUID uuid = event.getPlayer().getUniqueId();
            recentlyKicked.add(uuid);
            Bukkit.getScheduler().runTaskLater(plugin, () -> recentlyKicked.remove(uuid), 20L);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in CombatListener#onKick for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NonNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean wasKicked = recentlyKicked.remove(uuid);

        if (!config.combatLogPunishEnabled() || !combatManager.isInCombat(uuid)) {
            return;
        }

        if (Bukkit.isStopping()) {
            return;
        }
        if (wasKicked && !config.combatLogPunishOnKick()) {
            return;
        }

        try {
            logger.log(PoppyLogger.Category.COMBAT, player, "disconnected while in combat (combat log)");
            Bukkit.getServer().sendMessage(messages.get("combat.log-punished", "player", player.getName()));

            Location at = player.getLocation();
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    at.getWorld().dropItemNaturally(at, item);
                }
            }
            player.getInventory().clear();

            deathChestManager.suppressNextDeathChest(uuid);
            player.setHealth(0.0);

            // The nested death above still re-fires every PlayerDeathEvent listener
            // that runs before this one in the quit dispatch (this handler is
            // MONITOR, deliberately last) — undoing both known re-insertions here
            // rather than relying on registration order.
            backManager.remove(uuid);
            deathLocationManager.remove(uuid);

            combatManager.remove(uuid);
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