package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Handles /rtp: teleports the sender to a random safe spot around their
 * world's spawn, within the configured min/max radius.
 *
 * <p>Chunk lookups go through {@link World#getChunkAtAsync(int, int)} rather
 * than the synchronous {@code getChunkAt}, so an unexplored area doesn't
 * force chunk generation on the main thread and stall the server — this
 * matters a lot here since the default 5000-block max radius routinely
 * lands outside already-generated terrain.
 *
 * <p>{@link #inProgress} guards against a player spamming /rtp before their
 * first search resolves: without it, each call fires an independent async
 * chunk-generation chain (up to {@code rtp-max-attempts} chunk generations
 * each), so N rapid presses of /rtp in the same tick could queue up to
 * N x rtp-max-attempts concurrent chunk generations — on a fresh world,
 * that's enough to bring the server to its knees. The cooldown alone
 * doesn't help here since it's only applied on success (correctly — see
 * earlier fix), so it does nothing to stop a burst of presses before any
 * of them has resolved. {@link #inProgress} is removed on every exit path:
 * success, running out of attempts, the player going offline mid-search,
 * an exception, and on quit (see {@link #onQuit}) as a final safety net.
 *
 * <p>Unlike {@link #inProgress} (cleared on quit as a genuine safety net —
 * a lingering flag would otherwise permanently lock an offline player out
 * after reconnecting), {@link #lastUse} is deliberately <b>not</b> cleared
 * on quit: doing so would let a player reset their own cooldown for free
 * by disconnecting and reconnecting. The cooldown is meant to survive a
 * disconnect/reconnect, and only resets on a full server restart.
 *
 * <p>The Nether needs different vertical placement logic than the
 * Overworld/End: {@link World#getHighestBlockYAt(int, int)} finds the
 * highest block exposed to open sky, which doesn't exist in the Nether —
 * it would just return the underside of the solid bedrock roof, landing
 * the player on top of the world. See {@link #findNetherCandidate}.
 */
public class RtpCommand extends SafeCommand implements Listener {

    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyStats stats;

    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Set<UUID> inProgress = new HashSet<>();

    public RtpCommand(JavaPlugin plugin, TeleportManager teleportManager, Messages messages, PoppyConfig config, PoppyStats stats) {
        super(plugin, messages);
        this.teleportManager = teleportManager;
        this.config = config;
        this.stats = stats;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("rtp.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        if (!inProgress.add(player.getUniqueId())) {
            player.sendMessage(messages.get("rtp.already-searching"));
            return true;
        }

        attemptFindSafeLocation(player, player.getWorld().getSpawnLocation(), config.rtpMaxAttempts());
        return true;
    }

    /**
     * Tries one random candidate at a time, loading its chunk asynchronously so we never
     * force-generate terrain on the main thread. Recurses (still off the hot path) until
     * a safe spot is found or attempts run out.
     */
    private void attemptFindSafeLocation(Player player, Location center, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            inProgress.remove(player.getUniqueId());
            player.sendMessage(messages.get("rtp.failed"));
            return;
        }

        World world = center.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double distance = ThreadLocalRandom.current().nextDouble(config.rtpMinRadius(), config.rtpMaxRadius());
        int x = (int) (center.getX() + Math.cos(angle) * distance);
        int z = (int) (center.getZ() + Math.sin(angle) * distance);

        world.getChunkAtAsync(x >> 4, z >> 4)
                .thenAccept(chunk -> {
                    if (!player.isOnline()) {
                        inProgress.remove(player.getUniqueId());
                        return;
                    }

                    Location candidate = world.getEnvironment() == World.Environment.NETHER
                            ? findNetherCandidate(world, x, z)
                            : buildOverworldCandidate(world, x, z);

                    if (candidate != null && isSafe(candidate)) {
                        Home rtpHome = Home.fromLocation("rtp", candidate);
                        boolean accepted = teleportManager.requestTeleport(player, rtpHome, "rtp.success");

                        inProgress.remove(player.getUniqueId());

                        // Only consume the cooldown/stat if the teleport was actually accepted —
                        // a combat-tag rejection shouldn't cost the player their /rtp attempt.
                        if (accepted) {
                            lastUse.put(player.getUniqueId(), System.currentTimeMillis());
                            stats.incrementRtpUsed();
                        }
                    } else {
                        attemptFindSafeLocation(player, center, attemptsLeft - 1);
                    }
                })
                .exceptionally(throwable -> {
                    // thenAccept runs after execute(...) has already returned, so this is
                    // outside SafeCommand's try/catch — without this handler an exception
                    // here would just vanish silently inside the CompletableFuture.
                    inProgress.remove(player.getUniqueId());
                    plugin.getLogger().log(Level.SEVERE, "Error resolving a /rtp location for " + player.getName(), throwable);
                    if (player.isOnline()) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(messages.get("general.error")));
                    }
                    return null;
                });
    }

    private Location buildOverworldCandidate(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Scans downward from just below the Nether's solid bedrock roof,
     * returning the first vertical position that passes {@link #isSafe},
     * or null if the whole column is solid all the way down (common near
     * the roof itself, or in dense terrain). {@link #attemptFindSafeLocation}
     * simply retries at a new random column when this returns null.
     */
    private Location findNetherCandidate(World world, int x, int z) {
        int scanStart = Math.min(120, world.getMaxHeight() - 8);

        for (int y = scanStart; y > world.getMinHeight(); y--) {
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafe(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        inProgress.remove(event.getPlayer().getUniqueId());
    }

    private long cooldownRemaining(UUID uuid) {
        long cooldownMillis = config.rtpCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }

    private boolean isSafe(Location location) {
        Block ground = location.clone().subtract(0, 1, 0).getBlock();
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();

        Material groundType = ground.getType();
        if (!groundType.isSolid() || groundType == Material.MAGMA_BLOCK || groundType == Material.CACTUS) {
            return false;
        }
        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }
        return !feet.isLiquid() && !head.isLiquid();
    }
}