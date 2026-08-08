package fr.quentin.poppy.listeners.treecapitator;

import fr.quentin.poppy.manager.treecapitator.PlacedLogManager;
import fr.quentin.poppy.manager.treecapitator.TreeCapitatorManager;
import fr.quentin.poppy.util.BulkBreakGuard;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Implements treecapitator: when a player breaks a log block whose
 * material is in {@code treecapitator-materials} using an axe, while
 * their personal toggle (see {@link TreeCapitatorManager}) and the
 * plugin-wide {@code treecapitator-enabled} switch are both on, every
 * connected block of the same log material is broken too, up to
 * {@code treecapitator-max-blocks}. Adjacency follows
 * {@code treecapitator-diagonal} the same way {@code VeinMinerListener}
 * does for ores.
 *
 * <p>Restricted to axes deliberately — checked via a {@code "_AXE"} name
 * suffix, which does not accidentally match pickaxes (e.g.
 * {@code "IRON_PICKAXE"} ends in {@code "KAXE"}, not {@code "_AXE"}).
 *
 * <p>No experience is awarded for any of this — unlike ore mining,
 * chopping wood never grants XP in vanilla Minecraft, so there's nothing
 * to replicate here the way {@code VeinMinerListener} does for ores.
 * Leaves are left untouched entirely; vanilla's own leaf decay handles
 * them naturally once no log remains nearby.
 *
 * <p>Tool durability is tracked manually (see {@link #damageTool}),
 * respecting the Unbreaking enchantment's real probability of avoiding
 * damage; the fell stops early the moment the axe breaks.
 */
public class TreeCapitatorListener implements Listener {

    private static final int[][] FACE_OFFSETS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}
    };

    private static final int[][] ALL_OFFSETS = buildAllOffsets();

    private static int[][] buildAllOffsets() {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    offsets.add(new int[] {dx, dy, dz});
                }
            }
        }
        return offsets.toArray(new int[0][]);
    }

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final TreeCapitatorManager treeCapitatorManager;
    private final PlacedLogManager placedLogManager;

    public TreeCapitatorListener(JavaPlugin plugin, PoppyConfig config, TreeCapitatorManager treeCapitatorManager, PlacedLogManager placedLogManager) {
        this.plugin = plugin;
        this.config = config;
        this.treeCapitatorManager = treeCapitatorManager;
        this.placedLogManager = placedLogManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NonNull BlockBreakEvent event) {
        try {
            if (BulkBreakGuard.isActive()) {
                return;
            }

            if (!config.treecapitatorEnabled()) {
                return;
            }

            Player player = event.getPlayer();

            if (player.getGameMode() != GameMode.SURVIVAL) {
                return;
            }

            // A log the player themselves (or anyone) placed isn't part of a natural
            // tree — chaining from it could fell a wooden build instead. Skip the
            // whole feature for this break entirely rather than just excluding it
            // from the chain, since a placed log breaking on its own is never a
            // "start of a tree" in the first place.
            if (placedLogManager.isPlacedByPlayer(event.getBlock())) {
                return;
            }

            UUID uuid = player.getUniqueId();

            if (!treeCapitatorManager.isEnabled(uuid)) {
                return;
            }

            Material material = event.getBlock().getType();
            if (!config.treecapitatorMaterials().contains(material)) {
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!tool.getType().name().endsWith("_AXE")) {
                return;
            }

            List<Block> toBreak = findConnectedBlocks(event.getBlock(), material, config.treecapitatorMaxBlocks(), config.treecapitatorDiagonal());
            if (toBreak.isEmpty()) {
                return;
            }

            for (Block block : toBreak) {
                ItemStack currentTool = player.getInventory().getItemInMainHand();
                if (currentTool.getType() == Material.AIR) {
                    break;
                }

                BlockBreakEvent syntheticEvent = new BlockBreakEvent(block, player);
                BulkBreakGuard.run(() -> Bukkit.getPluginManager().callEvent(syntheticEvent));

                if (syntheticEvent.isCancelled()) {
                    continue;
                }

                if (syntheticEvent.isDropItems()) {
                    block.breakNaturally(currentTool, true);
                } else {
                    block.setType(Material.AIR);
                }

                if (!damageTool(player, currentTool)) {
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in TreeCapitatorListener#onBreak for " + event.getPlayer().getName(), e);
        }
    }

    /**
     * Flood-fills outward from {@code origin} over blocks of the same
     * material, capped at {@code maxBlocks} — the origin block itself is
     * excluded (it's already being broken by the triggering event).
     *
     * <p>Never loads or generates a chunk to look for more of the vein — a
     * player mining toward unexplored terrain, or along a chunk border
     * (worse with {@code veinminer-diagonal}, which can span up to 4 chunks
     * at an edge, 9 at a corner), could otherwise trigger a synchronous
     * chunk load (or full terrain generation) on the main thread for every
     * out-of-bounds neighbor — repeatable at will, no permission required.
     * {@link World#isChunkLoaded(int, int)} is checked using raw coordinates
     * BEFORE ever materializing a {@link Block} or calling {@code getType()}
     * on it, since either of those can themselves force the load. Neighbors
     * outside the world's actual height range are skipped the same way,
     * before doing any chunk-load check at all.
     *
     * <p>Uses packed {@code long} coordinates ({@link #packCoords}) for
     * {@code visited}/the work queue instead of {@link Block} instances —
     * avoids allocating a {@code CraftBlock} for every one of the up to
     * ~3,300 candidate neighbors scanned per break (128 blocks × 26
     * directions), only ever constructing a real {@link Block} once a
     * neighbor is confirmed loaded and worth inspecting.
     */
    private List<Block> findConnectedBlocks(Block origin, Material material, int maxBlocks, boolean diagonal) {
        int[][] offsets = diagonal ? ALL_OFFSETS : FACE_OFFSETS;

        World world = origin.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        Set<Long> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        List<Block> result = new ArrayList<>();

        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        visited.add(packCoords(ox, oy, oz));
        queue.add(new int[] {ox, oy, oz});

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            int[] current = queue.poll();

            for (int[] offset : offsets) {
                int nx = current[0] + offset[0];
                int ny = current[1] + offset[1];
                int nz = current[2] + offset[2];

                if (ny < minY || ny >= maxY) {
                    continue;
                }

                long key = packCoords(nx, ny, nz);
                if (!visited.add(key)) {
                    continue;
                }

                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                    continue;
                }

                Block neighbor = world.getBlockAt(nx, ny, nz);

                if (placedLogManager.isPlacedByPlayer(neighbor)) {
                    // Treated as a wall for the flood fill — never counted, never
                    // traversed through, so a player-built structure stops the chain
                    // rather than just being skipped-but-tunneled-past.
                    continue;
                }

                if (neighbor.getType() != material) {
                    continue;
                }

                result.add(neighbor);
                if (result.size() >= maxBlocks) {
                    break;
                }
                queue.add(new int[] {nx, ny, nz});
            }
        }

        return result;
    }

    /**
     * Packs a block position into a single {@code long} — x/z each get 26
     * bits (roughly ±33.5M, far beyond any real world border), y gets 12
     * bits after a +2048 offset (covers -2048..2047, comfortably beyond any
     * standard or extended world height range).
     */
    private static long packCoords(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) ((y + 2048) & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }

    private boolean damageTool(Player player, ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return true;
        }

        int unbreakingLevel = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreakingLevel > 0) {
            double chanceToTakeDamage = 1.0 / (unbreakingLevel + 1);
            if (ThreadLocalRandom.current().nextDouble() >= chanceToTakeDamage) {
                return true;
            }
        }

        int newDamage = damageable.getDamage() + 1;
        if (newDamage >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            return false;
        }

        damageable.setDamage(newDamage);
        tool.setItemMeta(damageable);
        return true;
    }
}