package fr.quentin.poppy.manager;

import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeleportManager implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final int warmupSeconds;
    private final boolean cancelOnMove;

    private final Map<UUID, BukkitTask> pendingTasks = new HashMap<>();
    private final Map<UUID, Location> startLocations = new HashMap<>();

    public TeleportManager(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.warmupSeconds = Math.max(0, plugin.getConfig().getInt("teleport-warmup-seconds", 3));
        this.cancelOnMove = plugin.getConfig().getBoolean("cancel-on-move", true);
    }

    public void requestTeleport(Player player, Home home) {
        requestTeleport(player, home, "home.success");
    }

    public void requestTeleport(Player player, Home home, String successMessagePath) {
        UUID uuid = player.getUniqueId();
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
                if (!player.isOnline()) {
                    cancelPending(uuid);
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
            }
        }.runTaskTimer(plugin, 0L, 20L);

        pendingTasks.put(uuid, task);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
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
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
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
    }

    private void cancelPending(UUID uuid) {
        BukkitTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        startLocations.remove(uuid);
    }

    private void teleportNow(Player player, Home home, String successMessagePath) {
        Location location = home.toLocation();
        if (location == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return;
        }

        player.teleport(location);
        player.sendMessage(messages.get(successMessagePath, "home", home.getName()));
    }
}