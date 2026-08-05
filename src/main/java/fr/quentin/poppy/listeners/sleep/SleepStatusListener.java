package fr.quentin.poppy.listeners.sleep;

import fr.quentin.poppy.manager.sleep.SleepPercentageListener;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
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
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Broadcasts a "X/Y players sleeping" message to everyone in the same world
 * whenever a player successfully enters a bed — a visible companion to
 * {@link SleepPercentageListener}. Rate-limited
 * per player via a shared {@link CooldownStore} ({@code sleep-status-cooldown-seconds}
 * in config.yml), so repeatedly entering/leaving a bed can't be used to
 * spam the broadcast.
 *
 * <p>Also detects when the night actually skips, for {@link PoppyLogger}'s
 * SLEEP category. Bukkit has no dedicated event for this, so it's inferred:
 * a lightweight repeating check compares each world's game time against
 * what natural progression would predict — this is a heuristic, not a
 * reliable detection (a manual {@code /time set} or another plugin
 * adjusting time could produce a false positive/negative), accepted since
 * the result is purely cosmetic (a log line), never gates game logic.
 * Worlds with {@code advanceTime} off, or with no players online, are
 * skipped entirely.
 *
 * <p>{@link #lastKnownTime}/{@link #wasSleeping} are keyed by world UID,
 * not player UUID, so {@link CooldownStore}'s per-player purge doesn't
 * apply to them — {@link #onWorldUnload} removes a world's entry when it
 * unloads, since nothing else would otherwise ever clean it up (relevant
 * for multiworld/minigame plugins that create and destroy worlds).
 */
public class SleepStatusListener implements Listener {

    private static final long CHECK_INTERVAL_TICKS = 20L;
    private static final long JUMP_THRESHOLD_TICKS = 200L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final CooldownStore broadcastCooldown;

    private final Map<UUID, Long> lastKnownTime = new HashMap<>();
    private final Map<UUID, Boolean> wasSleeping = new HashMap<>();

    public SleepStatusListener(JavaPlugin plugin, Messages messages, PoppyConfig config,
                               PoppyLogger logger, CooldownManager cooldownManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
        this.broadcastCooldown = cooldownManager.get("sleep-broadcast");

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

        if (broadcastCooldown.remainingSeconds(player.getUniqueId()) > 0) {
            // Silent throttle: entering/leaving a bed in a loop to spam the
            // "X/Y sleeping" message doesn't need its own error message, that
            // would just be a different flavor of the same spam.
            return;
        }
        broadcastCooldown.start(player.getUniqueId(), config.sleepStatusCooldownMillis());

        World world = player.getWorld();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                broadcastStatus(world, player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error broadcasting sleep status for " + player.getName(), e);
            }
        });
    }

    @EventHandler
    public void onWorldUnload(@NonNull WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        lastKnownTime.remove(worldId);
        wasSleeping.remove(worldId);
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
                if (world.getPlayers().isEmpty()) {
                    continue;
                }

                Boolean advanceTime = world.getGameRuleValue(GameRules.ADVANCE_TIME);
                if (!advanceTime) {
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