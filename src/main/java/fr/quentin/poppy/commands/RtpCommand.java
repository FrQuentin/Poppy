package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
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
import java.util.Map;
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
 */
public class RtpCommand extends SafeCommand implements Listener {

    private final TeleportManager teleportManager;
    private final PoppyStats stats;
    private final int minRadius;
    private final int maxRadius;
    private final int maxAttempts;
    private final long cooldownMillis;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public RtpCommand(JavaPlugin plugin, TeleportManager teleportManager, Messages messages, PoppyStats stats) {
        super(plugin, messages);
        this.teleportManager = teleportManager;
        this.stats = stats;
        this.minRadius = Math.max(0, plugin.getConfig().getInt("rtp-min-radius", 100));
        this.maxRadius = Math.max(minRadius + 1, plugin.getConfig().getInt("rtp-max-radius", 5000));
        this.maxAttempts = Math.max(1, plugin.getConfig().getInt("rtp-max-attempts", 20));
        this.cooldownMillis = Math.max(0, plugin.getConfig().getInt("rtp-cooldown-seconds", 30)) * 1000L;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("rtp.cooldown", "seconds", String.valueOf(remaining)));
            return true;
        }

        attemptFindSafeLocation(player, player.getWorld().getSpawnLocation(), maxAttempts);
        return true;
    }

    /**
     * Tries one random candidate at a time, loading its chunk asynchronously so we never
     * force-generate terrain on the main thread. Recurses (still off the hot path) until
     * a safe spot is found or attempts run out.
     */
    private void attemptFindSafeLocation(Player player, Location center, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            player.sendMessage(messages.get("rtp.failed"));
            return;
        }

        World world = center.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double distance = ThreadLocalRandom.current().nextDouble(minRadius, maxRadius);
        int x = (int) (center.getX() + Math.cos(angle) * distance);
        int z = (int) (center.getZ() + Math.sin(angle) * distance);

        world.getChunkAtAsync(x >> 4, z >> 4)
                .thenAccept(chunk -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    int y = world.getHighestBlockYAt(x, z);
                    Location candidate = new Location(world, x + 0.5, y + 1, z + 0.5);

                    if (isSafe(candidate)) {
                        lastUse.put(player.getUniqueId(), System.currentTimeMillis());
                        stats.incrementRtpUsed();
                        Home rtpHome = Home.fromLocation("rtp", candidate);
                        teleportManager.requestTeleport(player, rtpHome, "rtp.success");
                    } else {
                        attemptFindSafeLocation(player, center, attemptsLeft - 1);
                    }
                })
                .exceptionally(throwable -> {
                    // thenAccept runs after execute(...) has already returned, so this is
                    // outside SafeCommand's try/catch — without this handler an exception
                    // here would just vanish silently inside the CompletableFuture.
                    plugin.getLogger().log(Level.SEVERE, "Error resolving a /rtp location for " + player.getName(), throwable);
                    if (player.isOnline()) {
                        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(messages.get("general.error")));
                    }
                    return null;
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    private long cooldownRemaining(UUID uuid) {
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