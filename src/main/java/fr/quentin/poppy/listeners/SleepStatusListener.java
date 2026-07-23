package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Broadcasts a "X/Y players sleeping" message to everyone in the same world
 * whenever a player successfully enters a bed — a visible companion to
 * {@link fr.quentin.poppy.manager.SleepPercentageListener}, so players know
 * how close they are to skipping the night. Toggleable via
 * {@code sleep-status-message-enabled} in config.yml.
 *
 * <p>Only counts players in the same {@link World} as the sleeper, matching
 * the scope of the {@code playersSleepingPercentage} gamerule itself.
 *
 * <p>Also detects when the night actually skips, for {@link PoppyLogger}'s
 * SLEEP category. Bukkit has no dedicated event for this, so it's inferred:
 * a lightweight repeating check compares each world's game time against
 * what natural progression would predict; a sleep-skip advances time in one
 * large jump instead of the usual ~20 ticks/second, so a big discrepancy
 * right after players were sleeping is a reliable (if indirect) signal.
 */
public class SleepStatusListener implements Listener {

    private static final long CHECK_INTERVAL_TICKS = 20L;
    private static final long JUMP_THRESHOLD_TICKS = 200L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyLogger logger;
    private final boolean enabled;

    private final Map<UUID, Long> lastKnownTime = new HashMap<>();
    private final Map<UUID, Boolean> wasSleeping = new HashMap<>();

    public SleepStatusListener(JavaPlugin plugin, Messages messages, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.logger = logger;
        this.enabled = plugin.getConfig().getBoolean("sleep-status-message-enabled", true);

        Bukkit.getScheduler().runTaskTimer(plugin, this::checkForNightSkip, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
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

        logger.log(PoppyLogger.Category.SLEEP, player, sleeping + "/" + total + " players now sleeping in " + world.getName());

        for (Player online : world.getPlayers()) {
            online.sendMessage(messages.get("sleep.status",
                    "player", player.getName(),
                    "sleeping", String.valueOf(sleeping),
                    "total", String.valueOf(total)));
        }
    }

    /**
     * Runs every second for every loaded world, comparing the world's
     * current time against what one second of natural progression would
     * predict. A jump larger than {@link #JUMP_THRESHOLD_TICKS}, combined
     * with someone having been sleeping last check, is logged as a night
     * skip.
     */
    private void checkForNightSkip() {
        if (!enabled) {
            return;
        }

        try {
            for (World world : Bukkit.getWorlds()) {
                UUID worldId = world.getUID();
                long currentTime = world.getTime();
                boolean anyoneSleepingNow = !world.getPlayers().isEmpty()
                        && world.getPlayers().stream().anyMatch(Player::isSleeping);

                Long previousTime = lastKnownTime.get(worldId);
                boolean previouslySleeping = wasSleeping.getOrDefault(worldId, false);

                if (previousTime != null) {
                    long expectedTime = (previousTime + CHECK_INTERVAL_TICKS) % 24000L;
                    long delta = Math.abs(currentTime - expectedTime);

                    if (previouslySleeping && delta > JUMP_THRESHOLD_TICKS) {
                        logger.log(PoppyLogger.Category.SLEEP, "SYSTEM", "night skipped in " + world.getName());
                    }
                }

                lastKnownTime.put(worldId, currentTime);
                wasSleeping.put(worldId, anyoneSleepingNow);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in SleepStatusListener#checkForNightSkip", e);
        }
    }
}