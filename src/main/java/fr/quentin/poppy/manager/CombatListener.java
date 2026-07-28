package fr.quentin.poppy.manager;

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
 * block teleporting/flying for a short time after combat — see
 * {@code combat-tag-enabled} / {@code combat-tag-seconds} in config.yml.
 *
 * <p>Death clears the tag immediately rather than letting it linger for
 * its remaining duration: a dead player is by definition no longer in
 * combat.
 *
 * <p><b>Disconnecting during combat deliberately does NOT clear the
 * tag</b> — clearing it on quit is the classic "Alt+F4 to dodge a losing
 * fight" exploit. {@link CombatManager}'s map is already self-expiring,
 * so there's no memory-leak concern in leaving the entry alone here.
 *
 * <p><b>Combat-log punishment ({@link #onQuit}):</b> toggleable via
 * {@code combat-log-punish} in config.yml, a player who disconnects while
 * still tagged has their inventory dropped on the ground and cleared
 * <i>explicitly</i>, before {@link Player#setHealth(double)} is called —
 * not the other way around. This matters for two reasons, both found by
 * auditing an earlier version of this method that called
 * {@code setHealth(0)} directly:
 * <ul>
 *   <li>{@code PlayerQuitEvent} fires for far more than a voluntary
 *   disconnect — an admin {@code /kick}, an anti-cheat false positive, a
 *   network timeout, a mid-fight ban. {@link #onQuit} explicitly excludes
 *   a server shutdown ({@link Bukkit#isStopping()}) and, by default, a
 *   kick ({@link #recentlyKicked}, populated by {@link #onKick}) —
 *   {@code combat-log-punish-on-kick} in config.yml opts back into
 *   punishing kicks for servers that want anti-cheat kicks treated the
 *   same as a genuine log-out.</li>
 *   <li>{@code setHealth(0)} still triggers a real, synchronous
 *   {@link PlayerDeathEvent} nested inside the {@code PlayerQuitEvent}
 *   dispatch already in progress — every other {@code PlayerDeathEvent}
 *   listener still fires, including {@code BackListener#onDeath}, which
 *   unconditionally records a fresh {@code /back} location. Since the
 *   player is already disconnecting, no future {@code PlayerQuitEvent}
 *   will ever fire to clean that entry back out — a real, permanent leak
 *   of one {@link Location} (and the {@link org.bukkit.World} reference
 *   it holds) per combat-log, previously dependent on
 *   {@code BackListener} happening to be registered before this class in
 *   {@code Poppy#onEnable}. Clearing the inventory manually first at
 *   least closes the more serious item-duplication risk (nothing left for
 *   the nested death's own drop logic to touch), but the leak itself is
 *   independent of that and is closed explicitly instead:
 *   {@link BackManager#remove(UUID)} is called again right after
 *   {@code setHealth(0)}, undoing whatever the nested death re-inserted,
 *   without depending on listener registration order at all.</li>
 * </ul>
 * {@code setHealth(0)} itself is kept (rather than replaced with some
 * other death-marking mechanism) purely so the player still sees the
 * normal death screen when they reconnect — by the time it runs, their
 * inventory is already empty, so the nested death event has nothing left
 * to duplicate.
 */
public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final CombatManager combatManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final Messages messages;
    private final DeathChestManager deathChestManager;
    private final BackManager backManager;

    private final Set<UUID> recentlyKicked = new HashSet<>();

    public CombatListener(JavaPlugin plugin, CombatManager combatManager, PoppyConfig config, PoppyLogger logger,
                          Messages messages, DeathChestManager deathChestManager, BackManager backManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.config = config;
        this.logger = logger;
        this.messages = messages;
        this.deathChestManager = deathChestManager;
        this.backManager = backManager;
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

    /**
     * Populates {@link #recentlyKicked} — consumed directly by {@link #onQuit}
     * as soon as it fires, whenever that actually happens; the scheduled
     * removal here is only a safety net in case a {@link PlayerQuitEvent}
     * never follows this kick at all (the kick got cancelled by another
     * plugin, the connection was already gone, etc.). A short 1-tick cleanup
     * used to be here instead, but that was too aggressive: the actual
     * disconnect/quit dispatch for a kick isn't guaranteed to happen in the
     * very next tick (the server can defer it, e.g. to flush the disconnect
     * packet first), so removing the entry that early could race ahead of
     * the quit event it exists to gate — silently punishing a kicked player
     * as if they'd combat-logged, exactly the bug this was supposed to
     * prevent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(@NonNull PlayerKickEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        recentlyKicked.add(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentlyKicked.remove(uuid), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NonNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Consumed here, not left to the scheduled cleanup in onKick — this is what
        // actually closes the race described in onKick's doc.
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

            backManager.remove(uuid);

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