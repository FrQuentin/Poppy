package fr.quentin.poppy.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Single source of truth for "is this location safe to teleport a player
 * to" — previously duplicated, slightly differently, between
 * {@code RtpCommand#isSafe} and {@code DeathBackCommand#isSafe}. That
 * divergence was real and dangerous: {@code RtpCommand}'s version didn't
 * reject {@link Material#FIRE}/{@link Material#SOUL_FIRE} on the feet/head
 * blocks (neither solid nor liquid, so neither of its checks caught them),
 * which routinely landed a fresh, often unarmored player directly in fire
 * in the Nether via {@code findNetherCandidate}'s vertical scan.
 *
 * <p>The one deliberate behavioral difference between the two call sites
 * is {@code requireSolidGround}:
 * <ul>
 *   <li>{@code true} (used by {@code RtpCommand}) — the ground must be
 *   strictly solid, and the feet/head blocks must not be liquid either.
 *   A random teleport has no reason to ever drop a player in water.</li>
 *   <li>{@code false} (used by {@code DeathBackCommand}) — the ground can
 *   be solid OR liquid (water), and feet/head liquid isn't rejected. This
 *   preserves an earlier, deliberate fix: a player who drowned needs
 *   {@code /deathback} to be able to return them to their exact death
 *   spot in open water, not force them through the nearby-safe-spot
 *   search just because there's no dry ground underneath.</li>
 * </ul>
 * Both modes reject the same hazards ({@link #isHazard}) on all three of
 * the below/feet/head blocks — lava, fire, soul fire, magma, cactus, and
 * (a small unification improvement over the previous {@code DeathBackCommand}
 * behavior, which didn't check for it) a lit campfire.
 */
public final class SafetyCheck {

    private SafetyCheck() {
    }

    public static boolean isSafe(Location location, boolean requireSolidGround) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()) {
            return false;
        }

        Block below = location.clone().subtract(0, 1, 0).getBlock();
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();

        for (Block block : new Block[] {below, feet, head}) {
            if (isHazard(block.getType())) {
                return false;
            }
        }

        boolean hasFooting = requireSolidGround
                ? below.getType().isSolid()
                : (below.getType().isSolid() || below.isLiquid());
        if (!hasFooting) {
            return false;
        }

        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }

        if (requireSolidGround) {
            return !feet.isLiquid() && !head.isLiquid();
        }

        return true;
    }

    private static boolean isHazard(Material type) {
        return type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK || type == Material.CACTUS
                || type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE;
    }
}