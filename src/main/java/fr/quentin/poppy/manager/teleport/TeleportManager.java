package fr.quentin.poppy.manager.teleport;

import fr.quentin.poppy.manager.back.BackManager;
import fr.quentin.poppy.manager.combat.CombatManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
 * /tpa, /deathback, shared-home links). Config values are read live from
 * {@link PoppyConfig}, so {@code /poppy reload} applies immediately.
 *
 * <p>{@code requestTeleport} returns whether the request was accepted
 * (warmup started, or teleport dispatched) versus rejected outright
 * (combat tag) — callers with their own side effects gated on an actual
 * attempt (a cooldown, a stat) must check this.
 *
 * <p>An optional {@code onSuccess} callback runs only once the teleport has
 * actually completed (see {@link #teleportNow}), not merely once it was
 * requested or accepted.
 *
 * <p>The destination is resolved via a {@link Resolution} supplier,
 * re-resolved right before the actual teleport (not just once at request
 * time) — so a home/spawn deleted mid-warmup cancels instead of using
 * stale coordinates. {@link Resolution} carries an optional
 * {@code failureMessagePath} so a caller can distinguish WHY a
 * destination stopped being valid (e.g. {@code TpaAcceptCommand}
 * distinguishing "the destination went offline" from "the destination
 * re-entered combat during the warmup") — previously every such failure
 * showed the same generic {@code teleport.target-missing} message.
 *
 * <p><b>{@link #onTeleportComplete}/{@link #onTeleportFailed} only ever run
 * on the main thread</b> — both {@code teleportAsync(...)}'s callbacks in
 * {@link #teleportNow} explicitly re-check {@link Bukkit#isPrimaryThread()}
 * and defer if not, rather than assuming Paper's teleport future always
 * completes on the main thread.
 *
 * <p>{@link #pendingTasks}/{@link #startLocations} only hold entries for
 * the few seconds a warmup is active, so they're not a memory-leak risk
 * and don't need an explicit quit listener — the running
 * {@link BukkitRunnable} self-cleans via {@code !player.isOnline()} on its
 * very next tick.
 */
public class TeleportManager implements Listener {

    /**
     * A resolved (or failed-to-resolve) teleport destination. {@code home}
     * null means the teleport can't proceed; {@code failureMessagePath}, if
     * non-null, overrides the generic {@code teleport.target-missing}
     * message with something more specific to why.
     */
    public record Resolution(Home home, String failureMessagePath) {
        public static Resolution of(Home home) {
            return new Resolution(home, null);
        }
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final BackManager backManager;
    private final CombatManager combatManager;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    private final Map<UUID, BukkitTask> pendingTasks = new HashMap<>();
    private final Map<UUID, Location> startLocations = new HashMap<>();

    public TeleportManager(JavaPlugin plugin, Messages messages, PoppyConfig config, BackManager backManager,
                           CombatManager combatManager, PoppyStats stats, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.backManager = backManager;
        this.combatManager = combatManager;
        this.stats = stats;
        this.logger = logger;
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath) {
        return requestTeleportInternal(player, () -> Resolution.of(home), successMessagePath, null);
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath, Runnable onSuccess) {
        return requestTeleportInternal(player, () -> Resolution.of(home), successMessagePath, onSuccess);
    }

    public void requestTeleport(Player player, Supplier<Home> homeSupplier, String successMessagePath) {
        requestTeleportInternal(player, () -> Resolution.of(homeSupplier.get()), successMessagePath, null);
    }

    /**
     * Same as the {@link Supplier}{@code <Home>} overloads, but with a
     * {@link Resolution} supplier that can attach a specific failure
     * message — see {@link Resolution} for why that matters.
     */
    public void requestTeleportResolved(Player player, Supplier<Resolution> resolutionSupplier, String successMessagePath, Runnable onSuccess) {
        requestTeleportInternal(player, resolutionSupplier, successMessagePath, onSuccess);
    }

    private boolean requestTeleportInternal(Player player, Supplier<Resolution> resolutionSupplier, String successMessagePath, Runnable onSuccess) {
        UUID uuid = player.getUniqueId();

        if (combatManager.isInCombat(uuid)) {
            player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
            return false;
        }

        cancelPending(uuid);

        int warmupSeconds = config.teleportWarmupSeconds();
        if (warmupSeconds <= 0) {
            teleportNow(player, resolutionSupplier, successMessagePath, onSuccess);
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
                        teleportNow(player, resolutionSupplier, successMessagePath, onSuccess);
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
            if (startLocations.isEmpty()) {
                return;
            }

            if (!config.cancelOnMove()) {
                return;
            }

            UUID uuid = event.getPlayer().getUniqueId();
            Location from = startLocations.get(uuid);
            if (from == null) {
                return;
            }

            Location to = event.getTo();

            if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
                cancelPending(uuid);
                event.getPlayer().sendMessage(messages.get("teleport.cancelled-move"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in TeleportManager#onMove for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NonNull EntityDamageEvent event) {
        try {
            if (!config.cancelOnDamage()) {
                return;
            }

            if (event.getFinalDamage() <= 0) {
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

    protected void teleportNow(Player player, Supplier<Resolution> resolutionSupplier, String successMessagePath, Runnable onSuccess) {
        Resolution resolution = resolutionSupplier.get();
        Home home = resolution.home();
        if (home == null) {
            String path = resolution.failureMessagePath() != null ? resolution.failureMessagePath() : "teleport.target-missing";
            player.sendMessage(messages.get(path));
            return;
        }

        Location location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return;
        }

        // Captured before the teleport (the origin must reflect where the
        // player actually was), but not committed to BackManager until the
        // teleport is confirmed successful in onTeleportComplete.
        Location origin = player.getLocation();

        player.teleportAsync(location)
                .thenAccept(success -> {
                    if (!Bukkit.isPrimaryThread()) {
                        Bukkit.getScheduler().runTask(plugin, () -> onTeleportComplete(player, origin, home, success, successMessagePath, onSuccess));
                        return;
                    }
                    onTeleportComplete(player, origin, home, success, successMessagePath, onSuccess);
                })
                .exceptionally(throwable -> {
                    if (!Bukkit.isPrimaryThread()) {
                        Bukkit.getScheduler().runTask(plugin, () -> onTeleportFailed(player, home, throwable));
                        return null;
                    }
                    onTeleportFailed(player, home, throwable);
                    return null;
                });
    }

    /**
     * Only ever runs on the main thread — guaranteed by both callers in
     * {@link #teleportNow}.
     */
    public void onTeleportComplete(Player player, Location origin, Home home, boolean success, String successMessagePath, Runnable onSuccess) {
        if (!success) {
            plugin.getLogger().warning("Teleport of " + player.getName() + " to '" + home.name() + "' did not complete successfully");
            return;
        }

        backManager.recordLocation(player, origin);

        stats.incrementTeleports();

        logger.log(PoppyLogger.Category.TELEPORT, player, "teleported to '" + home.name() + "' at "
                + home.worldName() + ": " + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z());

        player.sendMessage(messages.get(successMessagePath, "home", home.name()));

        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    /**
     * Only ever runs on the main thread — same guarantee as
     * {@link #onTeleportComplete}.
     */
    private void onTeleportFailed(Player player, Home home, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, "Error teleporting " + player.getName() + " to '" + home.name() + "'", throwable);
        if (player.isOnline()) {
            player.sendMessage(messages.get("general.error"));
        }
    }
}