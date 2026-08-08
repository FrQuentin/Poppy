package fr.quentin.poppy.listeners.veinminer;

import fr.quentin.poppy.manager.veinminer.VeinMinerManager;
import fr.quentin.poppy.util.BulkBreakGuard;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Implements the actual veinmining: when a player breaks a block whose
 * material is in {@code veinminer-materials} using a pickaxe, while
 * their personal toggle (see {@link VeinMinerManager}), the live
 * {@code poppy.veinminer} permission, and the plugin-wide
 * {@code veinminer-enabled} switch are all satisfied, every connected
 * block of the same material is broken too, up to
 * {@code veinminer-max-blocks}.
 *
 * <p><b>Permission is re-checked live on every break, not just at
 * command dispatch</b> — {@link VeinMinerManager}'s per-player override
 * persists across a reload, a restart, and crucially across a
 * permissions plugin revoking {@code poppy.veinminer} (a VIP perk whose
 * grant expired). Without checking {@code player.hasPermission(...)}
 * here, a player who lost the node kept veinmining forever with no way
 * to even disable it themselves, and {@code veinminer-default-enabled: true}
 * would grant the feature to everyone regardless of the permission node.
 *
 * <p><b>Restricted to Survival</b> — Creative already has instant,
 * tool-free removal and infinite blocks; letting the chain logic run for
 * a Creative player served no purpose and could fell/mine a build
 * instantly with zero real cost.
 *
 * <p>Adjacency depends on {@code veinminer-diagonal}: off means only
 * blocks sharing a full face ({@link #FACE_OFFSETS}) count as connected;
 * on (the default) also counts blocks touching only at an edge or corner
 * ({@link #ALL_OFFSETS}) — a diagonal-only connection is a common real
 * shape for an ore pocket.
 *
 * <p><b>Every chained block goes through a real, synthetic
 * {@link BlockBreakEvent}</b> — {@link Block#breakNaturally} on its own
 * never fires one, which meant every protection plugin (WorldGuard,
 * GriefPrevention), every logging plugin (CoreProtect), and even
 * Poppy's own {@code DeathChestManager} were completely blind to the up
 * to 63 extra blocks destroyed per chain: a player could click one
 * unprotected block right at a claim's edge and have the flood fill
 * destroy dozens of protected blocks inside it, untracked and
 * unrollbackable. {@link BulkBreakGuard} suppresses this listener's (and
 * {@code TreeCapitatorListener}'s) own reaction to that synthetic event,
 * so a chain-broken block never starts its own nested flood fill.
 *
 * <p><b>{@link #findConnectedBlocks} never loads or generates a chunk to
 * look for more of the vein</b> — a player mining toward unexplored
 * terrain, or along a chunk border (worse with diagonals, which can span
 * up to 9 chunks at a corner), could otherwise trigger a synchronous
 * chunk load or full terrain generation on the main thread for every
 * out-of-bounds neighbor. {@link World#isChunkLoaded(int, int)} is
 * checked with raw coordinates before ever materializing a {@link Block}
 * or calling {@code getType()} on it. Neighbor positions outside the
 * world's real height range are skipped the same way. Visited/queued
 * positions are tracked as packed {@code long}s ({@link #packCoords})
 * rather than {@link Block} instances, avoiding an allocation for every
 * one of the up to ~3,300 candidate neighbors scanned per break.
 *
 * <p>Tool durability is tracked manually (see {@link #damageTool}),
 * respecting the Unbreaking enchantment's real probability of avoiding
 * damage; the vein stops early the moment the tool breaks.
 * {@code breakNaturally} never awards experience on its own either — see
 * {@link #rollExperience} for the manual XP roll, matching vanilla
 * ranges per ore and voiding it under Silk Touch.
 */
public class VeinMinerListener implements Listener {

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

    /**
     * Vanilla XP range (min, max inclusive) awarded when mining each ore
     * directly. Iron/copper/gold ores and ancient debris intentionally
     * have no entry — in vanilla, those award XP on smelting, not on
     * mining.
     */
    private static final Map<Material, int[]> ORE_XP_RANGES = Map.ofEntries(
            Map.entry(Material.COAL_ORE, new int[] {0, 2}),
            Map.entry(Material.DEEPSLATE_COAL_ORE, new int[] {0, 2}),
            Map.entry(Material.DIAMOND_ORE, new int[] {3, 7}),
            Map.entry(Material.DEEPSLATE_DIAMOND_ORE, new int[] {3, 7}),
            Map.entry(Material.EMERALD_ORE, new int[] {3, 7}),
            Map.entry(Material.DEEPSLATE_EMERALD_ORE, new int[] {3, 7}),
            Map.entry(Material.LAPIS_ORE, new int[] {2, 5}),
            Map.entry(Material.DEEPSLATE_LAPIS_ORE, new int[] {2, 5}),
            Map.entry(Material.REDSTONE_ORE, new int[] {1, 5}),
            Map.entry(Material.DEEPSLATE_REDSTONE_ORE, new int[] {1, 5}),
            Map.entry(Material.NETHER_QUARTZ_ORE, new int[] {2, 5}),
            Map.entry(Material.NETHER_GOLD_ORE, new int[] {0, 1})
    );

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final VeinMinerManager veinMinerManager;

    public VeinMinerListener(JavaPlugin plugin, PoppyConfig config, VeinMinerManager veinMinerManager) {
        this.plugin = plugin;
        this.config = config;
        this.veinMinerManager = veinMinerManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NonNull BlockBreakEvent event) {
        try {
            if (BulkBreakGuard.isActive()) {
                return;
            }

            if (!config.veinminerEnabled()) {
                return;
            }

            Player player = event.getPlayer();

            if (!player.hasPermission("poppy.veinminer")) {
                return;
            }

            if (player.getGameMode() != GameMode.SURVIVAL) {
                return;
            }

            UUID uuid = player.getUniqueId();

            if (!veinMinerManager.isEnabled(uuid)) {
                return;
            }

            Material material = event.getBlock().getType();
            if (!config.veinminerMaterials().contains(material)) {
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!tool.getType().name().endsWith("_PICKAXE")) {
                return;
            }

            List<Block> toBreak = findConnectedBlocks(event.getBlock(), material, config.veinminerMaxBlocks(), config.veinminerDiagonal());
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

                boolean silkTouch = currentTool.containsEnchantment(Enchantment.SILK_TOUCH);
                Material blockMaterial = block.getType();
                Location center = block.getLocation().add(0.5, 0.5, 0.5);

                if (syntheticEvent.isDropItems()) {
                    block.breakNaturally(currentTool, true);
                    int xp = rollExperience(blockMaterial, silkTouch);
                    if (xp > 0) {
                        block.getWorld().spawn(center, ExperienceOrb.class, orb -> orb.setExperience(xp));
                    }
                } else {
                    block.setType(Material.AIR);
                }

                if (!damageTool(player, currentTool)) {
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in VeinMinerListener#onBreak for " + event.getPlayer().getName(), e);
        }
    }

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
     * Packs a block position into a single {@code long} — x/z each get
     * 26 bits (roughly ±33.5M, far beyond any real world border), y gets
     * 12 bits after a +2048 offset (covers -2048..2047, comfortably
     * beyond any standard or extended world height range).
     */
    private static long packCoords(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) ((y + 2048) & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }

    private int rollExperience(Material material, boolean silkTouch) {
        if (silkTouch) {
            return 0;
        }

        int[] range = ORE_XP_RANGES.get(material);
        if (range == null) {
            return 0;
        }

        return range[0] + ThreadLocalRandom.current().nextInt(range[1] - range[0] + 1);
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