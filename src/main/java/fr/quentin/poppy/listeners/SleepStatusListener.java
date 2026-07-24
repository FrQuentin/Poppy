package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
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

public class SleepStatusListener implements Listener {

    private static final long CHECK_INTERVAL_TICKS = 20L;
    private static final long JUMP_THRESHOLD_TICKS = 200L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    private final Map<UUID, Long> lastKnownTime = new HashMap<>();
    private final Map<UUID, Boolean> wasSleeping = new HashMap<>();

    public SleepStatusListener(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;

        Bukkit.getScheduler().runTaskTimer(plugin, this::checkForNightSkip, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    @EventHandler
    public void onBedEnter(@NonNull PlayerBedEnterEvent event) {
        if (!config.sleepStatusMessageEnabled()) {
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        World world = player.getWorld();

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

    private void checkForNightSkip() {
        if (!config.sleepStatusMessageEnabled()) {
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