package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class RtpCommand extends SafeCommand implements Listener {

    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyStats stats;

    private final Map<UUID, Long> lastUse = new HashMap<>();

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
            player.sendMessage(messages.get("rtp.cooldown", "seconds", String.valueOf(remaining)));
            return true;
        }

        attemptFindSafeLocation(player, player.getWorld().getSpawnLocation(), config.rtpMaxAttempts());
        return true;
    }

    private void attemptFindSafeLocation(Player player, Location center, int attemptsLeft) {
        if (attemptsLeft <= 0) {
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
                        return;
                    }

                    Location candidate = world.getEnvironment() == World.Environment.NETHER
                            ? findNetherCandidate(world, x, z)
                            : buildOverworldCandidate(world, x, z);

                    if (candidate != null && isSafe(candidate)) {
                        Home rtpHome = Home.fromLocation("rtp", candidate);
                        boolean accepted = teleportManager.requestTeleport(player, rtpHome, "rtp.success");

                        if (accepted) {
                            lastUse.put(player.getUniqueId(), System.currentTimeMillis());
                            stats.incrementRtpUsed();
                        }
                    } else {
                        attemptFindSafeLocation(player, center, attemptsLeft - 1);
                    }
                })
                .exceptionally(throwable -> {
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
        lastUse.remove(event.getPlayer().getUniqueId());
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