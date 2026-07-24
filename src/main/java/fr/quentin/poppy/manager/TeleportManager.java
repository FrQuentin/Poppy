package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
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
 * /tpa, /deathback, shared-home links). Config values (warmup length,
 * cancel-on-move/damage) are read live from {@link PoppyConfig} rather
 * than cached, so {@code /poppy reload} applies immediately, even to a
 * warmup already in progress.
 *
 * <p>{@code requestTeleport} returns whether the request was accepted
 * (warmup started, or teleport dispatched) versus rejected outright
 * (combat tag) — callers with their own side effects gated on an actual
 * attempt (a cooldown, a stat) must check this.
 *
 * <p>The destination is a {@link Supplier<Home>}, re-resolved right before
 * the actual teleport — so a home/spawn deleted mid-warmup cancels instead
 * of using stale coordinates.
 */
public class TeleportManager implements Listener {

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

    public boolean requestTeleport(Player player, Home home) {
        return requestTeleport(player, () -> home, "home.success");
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath) {
        return requestTeleport(player, () -> home, successMessagePath);
    }

    public boolean requestTeleport(Player player, Supplier<Home> homeSupplier, String successMessagePath) {
        UUID uuid = player.getUniqueId();

        if (combatManager.isInCombat(uuid)) {
            player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
            return false;
        }

        cancelPending(uuid);

        int warmupSeconds = config.teleportWarmupSeconds();
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
            if (!config.cancelOnMove()) {
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
            if (!config.cancelOnDamage()) {
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

        player.teleportAsync(location)
                .thenAccept(success -> {
                    if (!success) {
                        plugin.getLogger().warning("Teleport of " + player.getName() + " to '" + home.name() + "' did not complete successfully");
                        return;
                    }

                    stats.incrementTeleports();

                    logger.log(PoppyLogger.Category.TELEPORT, player, "teleported to '" + home.name() + "' at "
                            + home.worldName() + ": " + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z());

                    player.sendMessage(messages.get(successMessagePath, "home", home.name()));
                })
                .exceptionally(throwable -> {
                    // thenAccept runs asynchronously relative to the caller, so without this
                    // handler an exception here would vanish silently inside the CompletableFuture
                    // — same reasoning as RtpCommand's identical .exceptionally(...).
                    plugin.getLogger().log(Level.SEVERE, "Error teleporting " + player.getName() + " to '" + home.name() + "'", throwable);
                    if (player.isOnline()) {
                        player.sendMessage(messages.get("general.error"));
                    }
                    return null;
                });
    }
}