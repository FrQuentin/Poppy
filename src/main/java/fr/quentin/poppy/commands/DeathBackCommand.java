package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.DeathLocationManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Internal command triggered by the clickable link in
 * {@link fr.quentin.poppy.listeners.DeathCoordsListener} — never meant to
 * be typed manually. Deliberately has no permission node in plugin.yml,
 * same reasoning as {@link PoppyGotoCommand}: it only ever teleports the
 * sender to their own last recorded death location, so there's nothing to
 * gatekeep beyond "you can only send this to yourself" — no arguments,
 * no way to target anyone else's death location.
 *
 * <p>Unlike {@code /back}, the death location is checked for hazards
 * before teleporting — a death by lava, fire, or fall into the void would
 * otherwise send the player straight back into whatever killed them. If
 * the exact death spot is unsafe, {@link #findNearbySafeSpot} searches a
 * small radius around it for the closest safe alternative, so the player
 * still lands close enough to recover their items on foot.
 *
 * <p>The search radius is small (8 blocks) and runs synchronously — unlike
 * {@code RtpCommand}'s much larger radius, this is cheap enough not to
 * need the async chunk-loading treatment.
 */
public class DeathBackCommand extends SafeCommand {

    private static final int SEARCH_RADIUS = 8;

    private final DeathLocationManager deathLocationManager;
    private final TeleportManager teleportManager;

    public DeathBackCommand(JavaPlugin plugin, DeathLocationManager deathLocationManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.deathLocationManager = deathLocationManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        Location deathLocation = deathLocationManager.getLastDeath(player.getUniqueId());
        if (deathLocation == null) {
            player.sendMessage(messages.get("death.no-death-location"));
            return true;
        }

        if (deathLocation.getWorld() == null) {
            player.sendMessage(messages.get("general.world-not-loaded"));
            return true;
        }

        Location destination = isSafe(deathLocation) ? deathLocation : findNearbySafeSpot(deathLocation);

        if (destination == null) {
            player.sendMessage(messages.get("death.unsafe"));
            return true;
        }

        Home deathHome = Home.fromLocation("death", destination);
        teleportManager.requestTeleport(player, deathHome, "death.teleport-success");
        return true;
    }

    /**
     * Scans a cube of {@link #SEARCH_RADIUS} blocks around the death
     * location and returns the closest safe spot, or null if none is
     * found within range. "Closest" uses squared distance so the search
     * doesn't favor any particular axis.
     */
    private Location findNearbySafeSpot(Location deathLocation) {
        World world = deathLocation.getWorld();
        Location best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // already checked as the exact death spot
                    }

                    Location candidate = deathLocation.clone().add(dx, dy, dz);
                    if (!isSafe(candidate)) {
                        continue;
                    }

                    double distanceSquared = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = new Location(world, candidate.getBlockX() + 0.5, candidate.getBlockY(), candidate.getBlockZ() + 0.5,
                                deathLocation.getYaw(), deathLocation.getPitch());
                    }
                }
            }
        }

        return best;
    }

    /**
     * Checks the given location itself for hazards. Called both on the
     * exact death spot and on every candidate scanned by
     * {@link #findNearbySafeSpot}.
     */
    private boolean isSafe(Location location) {
        World world = location.getWorld();
        if (location.getY() < world.getMinHeight()) {
            return false;
        }

        Block below = location.clone().subtract(0, 1, 0).getBlock();
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();

        Material belowType = below.getType();
        if (!belowType.isSolid() || belowType == Material.MAGMA_BLOCK || belowType == Material.CACTUS) {
            return false;
        }

        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }

        return !feet.isLiquid() && !head.isLiquid();
    }
}