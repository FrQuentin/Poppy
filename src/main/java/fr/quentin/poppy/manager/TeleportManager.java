package fr.quentin.poppy.manager;

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
 * requested or accepted — this matters for callers like
 * {@code DeathBackCommand}, which needs to invalidate the death-location
 * link only after the player genuinely arrives, not just because the
 * warmup started (a combat-tag rejection during warmup would otherwise
 * burn the link for a teleport that never happened).
 *
 * <p>The destination is a {@link Supplier<Home>}, re-resolved right before
 * the actual teleport — so a home/spawn deleted mid-warmup cancels instead
 * of using stale coordinates.
 *
 * <p><b>{@link #onTeleportComplete}/{@link #onTeleportFailed} only ever run
 * on the main thread</b> — both {@code teleportAsync(...)}'s
 * {@code thenAccept}/{@code exceptionally} callbacks in {@link #teleportNow}
 * explicitly re-check {@link Bukkit#isPrimaryThread()} and defer via
 * {@link Bukkit#getScheduler()} if not, rather than assuming Paper's
 * teleport future always completes on the main thread. It does today, but
 * that's an implementation detail — the exact same class of assumption
 * {@code RtpCommand}'s {@code handleChunkLoaded} explicitly guards against.
 * An inconsistency between the two classes on this point was a sign one of
 * them was wrong, not that the assumption was safe; both now share the
 * same guard. This matters because these callbacks touch Bukkit API
 * (messaging, {@link PoppyStats}, {@link PoppyLogger}) and the caller's
 * {@code onSuccess} callback, which can itself mutate plain (unsynchronized)
 * collections — e.g. {@code DeathLocationManager#remove}.
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
        return requestTeleport(player, () -> home, "home.success", null);
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath) {
        return requestTeleport(player, () -> home, successMessagePath, null);
    }

    public boolean requestTeleport(Player player, Home home, String successMessagePath, Runnable onSuccess) {
        return requestTeleport(player, () -> home, successMessagePath, onSuccess);
    }

    public boolean requestTeleport(Player player, Supplier<Home> homeSupplier, String successMessagePath) {
        return requestTeleport(player, homeSupplier, successMessagePath, null);
    }

    /**
     * Same as {@link #requestTeleport(Player, Supplier, String)}, but with
     * an {@code onSuccess} callback run only once the teleport has actually
     * completed — see the class-level doc for why that distinction matters.
     *
     * @return true if the request was accepted (warmup started, or an
     *         instant teleport was dispatched); false if rejected outright
     *         because the player is combat-tagged.
     */
    public boolean requestTeleport(Player player, Supplier<Home> homeSupplier, String successMessagePath, Runnable onSuccess) {
        UUID uuid = player.getUniqueId();

        if (combatManager.isInCombat(uuid)) {
            player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
            return false;
        }

        cancelPending(uuid);

        int warmupSeconds = config.teleportWarmupSeconds();
        if (warmupSeconds <= 0) {
            teleportNow(player, homeSupplier, successMessagePath, onSuccess);
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
                        teleportNow(player, homeSupplier, successMessagePath, onSuccess);
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

            // Same guard as FlyManager#onDamage (and CombatListener#onDamage): a
            // hit cancelled by a protection plugin, or one dealing zero final
            // damage (a snowball, a fully-absorbed hit), is not "you took
            // damage" — without this, spamming snowballs at someone from a
            // protected zone cancelled their teleport warmup repeatedly, for
            // free, with zero actual damage ever dealt.
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

    protected void teleportNow(Player player, Supplier<Home> homeSupplier, String successMessagePath, Runnable onSuccess) {
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
                    if (!Bukkit.isPrimaryThread()) {
                        Bukkit.getScheduler().runTask(plugin, () -> onTeleportComplete(player, home, success, successMessagePath, onSuccess));
                        return;
                    }
                    onTeleportComplete(player, home, success, successMessagePath, onSuccess);
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
     * {@link #teleportNow}. Safe to touch Bukkit API and mutable state here.
     */
    private void onTeleportComplete(Player player, Home home, boolean success, String successMessagePath, Runnable onSuccess) {
        if (!success) {
            plugin.getLogger().warning("Teleport of " + player.getName() + " to '" + home.name() + "' did not complete successfully");
            return;
        }

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