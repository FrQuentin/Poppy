package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Broadcasts a "X/Y players sleeping" message to everyone in the same world
 * whenever a player successfully enters a bed — a visible companion to
 * {@link SleepPercentageListener}, so players know how close they are to
 * skipping the night. Toggleable via {@code sleep-status-message-enabled}
 * in config.yml.
 *
 * <p>Only counts players in the same {@link World} as the sleeper, matching
 * the scope of the {@code playersSleepingPercentage} gamerule itself.
 */
public class SleepStatusListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final boolean enabled;

    public SleepStatusListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.enabled = plugin.getConfig().getBoolean("sleep-status-message-enabled", true);
    }

    @EventHandler
    public void onBedEnter(@NonNull PlayerBedEnterEvent event) {
        if (!enabled) {
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        World world = player.getWorld();

        // Scheduled a tick later so the player's own isSleeping() has updated by
        // the time we count — it isn't guaranteed to already be true at the exact
        // moment this event fires.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                broadcastStatus(world, player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error broadcasting sleep status for " + player.getName(), e);
            }
        });
    }

    private void broadcastStatus(World world, Player player) {
        int sleeping = 0;
        int total = 0;

        for (Player online : world.getPlayers()) {
            total++;
            if (online.isSleeping()) {
                sleeping++;
            }
        }

        for (Player online : world.getPlayers()) {
            online.sendMessage(messages.get("sleep.status",
                    "player", player.getName(),
                    "sleeping", String.valueOf(sleeping),
                    "total", String.valueOf(total)));
        }
    }
}