package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.*;
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

import java.util.HashSet;
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
 * force chunk generation on the main thread and stall the server.
 *
 * <p><b>A failed search consumes the cooldown, a combat-tag rejection does
 * not:</b> a search running out of attempts still costs up to
 * {@code rtp-max-attempts} chunk generations — expensive — so it must not
 * be free to retry instantly; a combat-tag rejection is rejected by
 * {@link TeleportManager} before any chunk work happens at all, so it
 * stays free.
 *
 *
 * <p>The cooldown itself lives in a shared {@link CooldownStore} — see its
 * class-level doc for why that's preferable to a raw
 * {@code Map<UUID, Long>} that's never purged. {@link #inProgress} is a
 * separate {@link Set}, not a cooldown: it tracks an in-flight search, not
 * an expiry, and is cleared on quit as a genuine safety net (a lingering
 * flag would otherwise permanently lock an offline player out after
 * reconnecting) — unlike the cooldown, which is deliberately never reset
 * by disconnecting.
 *
 * <p>The Nether needs different vertical placement logic than the
 * Overworld/End: {@link World#getHighestBlockYAt(int, int)} finds the
 * highest block exposed to open sky, which doesn't exist in the Nether —
 * see {@link #findNetherCandidate}.
 */
public class RtpCommand extends SafeCommand implements Listener {

    private final TeleportManager teleportManager;
    private final PoppyConfig config;
    private final PoppyStats stats;
    private final CooldownStore cooldown;

    private final Set<UUID> inProgress = new HashSet<>();

    public RtpCommand(JavaPlugin plugin, TeleportManager teleportManager, Messages messages, PoppyConfig config, PoppyStats stats, CooldownRegistry registry) {
        super(plugin, messages);
        this.teleportManager = teleportManager;
        this.config = config;
        this.stats = stats;
        this.cooldown = new CooldownStore(plugin);
        registry.register("Random Teleport", cooldown);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("rtp.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        if (!inProgress.add(player.getUniqueId())) {
            player.sendMessage(messages.get("rtp.already-searching"));
            return true;
        }

        if (inProgress.size() > config.rtpMaxConcurrentSearches()) {
            inProgress.remove(player.getUniqueId());
            player.sendMessage(messages.get("rtp.server-busy"));
            return true;
        }

        attemptFindSafeLocation(player, player.getWorld().getSpawnLocation(), config.rtpMaxAttempts());
        return true;
    }

    private void attemptFindSafeLocation(Player player, Location center, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            inProgress.remove(player.getUniqueId());
            cooldown.start(player.getUniqueId(), config.rtpCooldownMillis());
            player.sendMessage(messages.get("rtp.failed"));
            return;
        }

        World world = center.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double distance = ThreadLocalRandom.current().nextDouble(config.rtpMinRadius(), config.rtpMaxRadius());
        int x = (int) (center.getX() + Math.cos(angle) * distance);
        int z = (int) (center.getZ() + Math.sin(angle) * distance);

        world.getChunkAtAsync(x >> 4, z >> 4)
                .thenRun(() -> handleChunkLoaded(player, world, x, z, center, attemptsLeft))
                .exceptionally(throwable -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        inProgress.remove(player.getUniqueId());
                        plugin.getLogger().log(Level.SEVERE, "Error resolving a /rtp location for " + player.getName(), throwable);
                        if (player.isOnline()) {
                            player.sendMessage(messages.get("general.error"));
                        }
                    });
                    return null;
                });
    }

    private void handleChunkLoaded(Player player, World world, int x, int z, Location center, int attemptsLeft) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleChunkLoaded(player, world, x, z, center, attemptsLeft));
            return;
        }

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

            if (accepted) {
                cooldown.start(player.getUniqueId(), config.rtpCooldownMillis());
                stats.incrementRtpUsed();
            }
        } else {
            attemptFindSafeLocation(player, center, attemptsLeft - 1);
        }
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
        inProgress.remove(event.getPlayer().getUniqueId());
    }

    private boolean isSafe(Location location) {
        return SafetyCheck.isSafe(location, true);
    }
}