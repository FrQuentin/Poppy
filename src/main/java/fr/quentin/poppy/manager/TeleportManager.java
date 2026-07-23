package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Central point for every Poppy teleport (/home, /spawn, /back, /rtp,
 * /tpa, /deathback, shared-home links): applies the configured warmup
 * countdown, cancels it on movement or damage, and blocks the whole
 * request while the player is combat-tagged (see {@link CombatManager}).
 *
 * <p>Being the single choke point for all teleports also makes this the
 * highest-leverage place to log teleport activity via {@link PoppyLogger}
 * — one hook here covers every teleport command at once, rather than
 * duplicating a log call in each command class.
 *
 * <p>{@code requestTeleport} returns a boolean indicating whether the
 * request was actually accepted (warmup started, or teleport dispatched)
 * versus rejected outright (combat tag) — callers that apply their own
 * side effects gated on a successful attempt, like {@code RtpCommand}'s
 * cooldown, must check this return value rather than assuming the request
 * always proceeds; otherwise a combat-tag rejection would still consume
 * things like a cooldown for a teleport that never happened.
 *
 * <p>The destination is resolved via a {@link Supplier<Home>} rather than
 * a plain {@link Home}, and re-resolved right before the actual teleport
 * (not just once at request time) — this matters for /home and /spawn
 * specifically: without it, deleting the home/spawn during the warmup
 * would still teleport the player to the now-stale coordinates. Every
 * other caller just wraps a fixed {@link Home} in a trivial supplier via
 * {@link #requestTeleport(Player, Home, String)}.
 *
 * <p>{@code pendingTasks} and {@code startLocations} only hold entries for
 * the few seconds a warmup is active — unlike {@link HomeManager}'s cache or
 * {@link BackManager}'s locations, they are not a memory-leak risk and don't
 * need an explicit quit listener: the running {@link BukkitRunnable} already
 * self-cleans via {@code !player.isOnline()} on its very next tick.
 */
public class TeleportManager implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final int warmupSeconds;
    private final BackManager backManager;
    private final CombatManager combatManager;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    private final boolean cancelOnMove;
    private final boolean cancelOnDamage;

    private final Map<UUID, BukkitTask> pendingTasks = new HashMap<>();
    private final Map<UUID, Location> startLocations = new HashMap<>();

    public TeleportManager(JavaPlugin plugin, Messages messages, BackManager backManager, CombatManager combatManager,
                           PoppyStats stats, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.backManager = backManager;
        this.combatManager = combatManager;
        this.stats = stats;
        this.logger = logger;
        this.warmupSeconds = Math.max(0, plugin.getConfig().getInt("teleport-warmup-seconds", 3));
        this.cancelOnMove = plugin.getConfig().getBoolean("cancel-on-move", true);
        this.cancelOnDamage = plugin.getConfig().getBoolean("cancel-on-damage", true);
    }

    public boolean requestTeleport(Player player, Home home) {
        return requestTeleport(player, () -> home, "home.success");
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath) {
        return requestTeleport(player, () -> home, successMessagePath);
    }

    /**
     * Same as {@link #requestTeleport(Player, Home, String)}, but the
     * destination is looked up fresh — via {@code homeSupplier} — both now
     * and again right before the actual teleport, so a destination that
     * stops existing during the warmup cancels the teleport instead of
     * using stale coordinates.
     *
     * @return true if the request was accepted (warmup started, or an
     *         instant teleport was dispatched); false if rejected outright
     *         because the player is combat-tagged. Callers whose own side
     *         effects (a cooldown, a stat increment...) should only apply
     *         on an actual attempt must check this.
     */
    public boolean requestTeleport(Player player, Supplier<Home> homeSupplier, String successMessagePath) {
        UUID uuid = player.getUniqueId();

        if (combatManager.isInCombat(uuid)) {
            player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
            return false;
        }

        cancelPending(uuid);

        if (warmupSeconds <= 0) {
            teleportNow(player, homeSupplier, successMessagePath);
            return true;
        }

        startLocations.put(uuid, player.getLocation());

        BukkitTask task = new BukkitRunnable() {
            int remaining = warmupSeconds;

            @Override
            public void run() {
                try {
                    if (!player.isOnline()) {
                        cancelPending(uuid);
                        cancel();
                        return;
                    }

                    if (combatManager.isInCombat(uuid)) {
                        cancelPending(uuid);
                        player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
                        cancel();
                        return;
                    }

                    if (remaining <= 0) {
                        pendingTasks.remove(uuid);
                        startLocations.remove(uuid);
                        teleportNow(player, homeSupplier, successMessagePath);
                        cancel();
                        return;
                    }

                    player.sendActionBar(messages.get("teleport.warmup-actionbar", "seconds", String.valueOf(remaining)));
                    remaining--;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error during teleport warmup for " + player.getName(), e);
                    cancelPending(uuid);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);

        pendingTasks.put(uuid, task);
        return true;
    }

    @EventHandler
    public void onMove(@NonNull PlayerMoveEvent event) {
        try {
            if (!cancelOnMove || startLocations.isEmpty()) {
                return;
            }

            UUID uuid = event.getPlayer().getUniqueId();
            Location from = startLocations.get(uuid);
            if (from == null) {
                return;
            }

            Location to = event.getTo();
            if (to == null) {
                return;
            }

            if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
                cancelPending(uuid);
                event.getPlayer().sendMessage(messages.get("teleport.cancelled-move"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in TeleportManager#onMove for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onDamage(@NonNull EntityDamageEvent event) {
        try {
            if (!cancelOnDamage) {
                return;
            }

            if (!(event.getEntity() instanceof Player player)) {
                return;
            }

            UUID uuid = player.getUniqueId();
            if (pendingTasks.containsKey(uuid)) {
                cancelPending(uuid);
                player.sendMessage(messages.get("teleport.cancelled-damage"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in TeleportManager#onDamage", e);
        }
    }

    private void cancelPending(UUID uuid) {
        BukkitTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        startLocations.remove(uuid);
    }

    protected void teleportNow(Player player, Supplier<Home> homeSupplier, String successMessagePath) {
        Home home = homeSupplier.get();
        if (home == null) {
            player.sendMessage(messages.get("teleport.target-missing"));
            return;
        }

        Location location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return;
        }

        backManager.recordLocation(player);

        player.teleportAsync(location).thenAccept(success -> {
            if (!success) {
                plugin.getLogger().warning("Teleport of " + player.getName() + " to '" + home.name() + "' did not complete successfully");
                return;
            }

            stats.incrementTeleports();

            logger.log(PoppyLogger.Category.TELEPORT, player, "teleported to '" + home.name() + "' at "
                    + home.worldName() + ": " + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z());

            player.sendMessage(messages.get(successMessagePath, "home", home.name()));
        });
    }
}