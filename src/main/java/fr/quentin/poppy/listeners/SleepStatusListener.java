package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
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
    private final Map<UUID, Long> lastBroadcast = new HashMap<>();

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

        if (broadcastCooldownRemaining(player.getUniqueId()) > 0) {
            // Silent throttle: entering/leaving a bed in a loop to spam the
            // "X/Y sleeping" message doesn't need its own error message, that
            // would just be a different flavor of the same spam.
            return;
        }
        lastBroadcast.put(player.getUniqueId(), System.currentTimeMillis());

        World world = player.getWorld();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                broadcastStatus(world, player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error broadcasting sleep status for " + player.getName(), e);
            }
        });
    }

    private long broadcastCooldownRemaining(UUID uuid) {
        long cooldownMillis = config.sleepStatusCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastBroadcast.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
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
     *
     * <p>This is a heuristic, not a reliable detection: Bukkit has no
     * dedicated "sleep skipped the night" event to listen to instead. A
     * manual {@code /time set}, another plugin adjusting time, or anything
     * else that moves the clock by a large jump while a player happens to be
     * sleeping can produce a false positive; a partial/gradual skip (rare,
     * but the vanilla algorithm doesn't strictly guarantee a single instant
     * jump every time) could produce a false negative. This is accepted
     * rather than engineered around further: the result is purely cosmetic
     * (a {@link PoppyLogger} line), never gates any actual game logic, so
     * perfect accuracy isn't worth the added complexity. Worlds with
     * {@code doDaylightCycle} off are skipped entirely, since on those
     * servers a time change is almost always a manual {@code /time set}
     * rather than an actual sleep-driven skip — the single most common
     * source of false positives this check would otherwise produce. Worlds
     * with no players online are also skipped, since there's nothing
     * meaningful to detect there.
     */
    private void checkForNightSkip() {
        if (!config.sleepStatusMessageEnabled()) {
            return;
        }

        try {
            for (World world : Bukkit.getWorlds()) {
                if (world.getPlayers().isEmpty()) {
                    continue;
                }

                if (!world.getGameRuleValue(GameRules.ADVANCE_TIME)) {
                    continue;
                }

                UUID worldId = world.getUID();
                long currentTime = world.getTime();
                boolean anyoneSleepingNow = world.getPlayers().stream().anyMatch(Player::isSleeping);

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