package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
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
import java.util.logging.Level;

/**
 * Central point for every Poppy teleport (/home, /spawn, /back, /rtp,
 * shared-home links): applies the configured warmup countdown, cancels it
 * on movement or damage, and blocks the whole request while the player is
 * combat-tagged (see {@link CombatManager}).
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
    private final boolean cancelOnMove;
    private final BackManager backManager;
    private final CombatManager combatManager;
    private final PoppyStats stats;

    private final Map<UUID, BukkitTask> pendingTasks = new HashMap<>();
    private final Map<UUID, Location> startLocations = new HashMap<>();

    public TeleportManager(JavaPlugin plugin, Messages messages, BackManager backManager, CombatManager combatManager, PoppyStats stats) {
        this.plugin = plugin;
        this.messages = messages;
        this.backManager = backManager;
        this.combatManager = combatManager;
        this.stats = stats;
        this.warmupSeconds = Math.max(0, plugin.getConfig().getInt("teleport-warmup-seconds", 3));
        this.cancelOnMove = plugin.getConfig().getBoolean("cancel-on-move", true);
    }

    public void requestTeleport(Player player, Home home) {
        requestTeleport(player, home, "home.success");
    }

    public void requestTeleport(Player player, Home home, String successMessagePath) {
        UUID uuid = player.getUniqueId();

        if (combatManager.isInCombat(uuid)) {
            player.sendMessage(messages.get("combat.in-combat", "seconds", String.valueOf(combatManager.remainingSeconds(uuid))));
            return;
        }

        cancelPending(uuid);

        if (warmupSeconds <= 0) {
            teleportNow(player, home, successMessagePath);
            return;
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
                        teleportNow(player, home, successMessagePath);
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
    }

    @EventHandler
    public void onMove(@NonNull PlayerMoveEvent event) {
        try {
            if (!cancelOnMove) {
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
            if (!cancelOnMove) {
                return;
            }

            if (!(event.getEntity() instanceof Player player)) {
                return;
            }

            UUID uuid = player.getUniqueId();
            if (pendingTasks.containsKey(uuid)) {
                cancelPending(uuid);
                player.sendMessage(messages.get("teleport.cancelled-move"));
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

    protected void teleportNow(Player player, Home home, String successMessagePath) {
        Location location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return;
        }

        backManager.recordLocation(player);
        player.teleport(location);
        stats.incrementTeleports();
        player.sendMessage(messages.get(successMessagePath, "home", home.name()));
    }
}