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
 * <p>The link stays usable across multiple attempts (combat tag rejection,
 * moving during warmup, etc.) and is only invalidated — via
 * {@link DeathLocationManager#remove} — once the teleport has genuinely
 * completed, passed as an {@code onSuccess} callback to
 * {@link TeleportManager#requestTeleport(Player, Home, String, Runnable)}.
 * Without this, a rejected or cancelled attempt would burn the link for a
 * teleport that never actually happened.
 */
public class DeathBackCommand extends SafeCommand {

    private static final int SEARCH_RADIUS = 5;

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
        teleportManager.requestTeleport(player, deathHome, "death.teleport-success",
                () -> deathLocationManager.remove(player.getUniqueId()));
        return true;
    }

    /**
     * Scans a cube of {@link #SEARCH_RADIUS} blocks around the death
     * location and returns the closest safe spot, or null if none is found
     * within range. "Closest" uses squared distance so the search doesn't
     * favor any particular axis.
     *
     * <p>Bounded to a smaller radius than the original 8 (17^3 = 4,913
     * candidates, up to 3 block reads each, all on the main thread) — the
     * death location can be far from the player clicking the link (they may
     * have moved on, or died somewhere they'd already left), and reading a
     * block in an unloaded chunk forces a synchronous chunk load. This is the
     * same class of cost {@code DeathChestManager#findPlacementSpot} was
     * refactored to avoid; a smaller radius keeps this in line with that
     * care while still covering the realistic case (landing "near" where you
     * died, not several blocks underground). {@code dy} bounds are also
     * checked up front to skip candidates outside the world's height range
     * entirely rather than reading a block for each of them.
     */
    private Location findNearbySafeSpot(Location deathLocation) {
        World world = deathLocation.getWorld();
        Location best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                int candidateY = deathLocation.getBlockY() + dy;
                if (candidateY < world.getMinHeight() || candidateY >= world.getMaxHeight()) {
                    continue;
                }

                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
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
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()) {
            return false;
        }

        Block below = location.clone().subtract(0, 1, 0).getBlock();
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();

        for (Block block : new Block[] {below, feet, head}) {
            Material type = block.getType();
            if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                    || type == Material.MAGMA_BLOCK || type == Material.CACTUS) {
                return false;
            }
        }

        // Below must be something the player actually rests on — solid ground OR a
        // liquid (water is buoyant, no fall) — but not empty air. This is narrower
        // than a plain "not solid" check (which would accept a fall into open air
        // above a hazard, the original bug this method was fixed for) while still
        // deliberately not requiring strictly solid ground like RtpCommand.isSafe
        // does: a solid-only requirement would reject a water-death location
        // entirely, sending a drowned player through a search radius instead of
        // straight back to where they actually died.
        boolean hasFooting = below.getType().isSolid() || below.isLiquid();
        if (!hasFooting) {
            return false;
        }

        return !feet.getType().isSolid() && !head.getType().isSolid();
    }
}