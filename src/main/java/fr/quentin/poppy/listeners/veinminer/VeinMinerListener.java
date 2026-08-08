package fr.quentin.poppy.listeners.veinminer;

import fr.quentin.poppy.manager.veinminer.VeinMinerManager;
import fr.quentin.poppy.util.BulkBreakGuard;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.*;
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
 * their personal toggle (see {@link VeinMinerManager}) and the
 * plugin-wide {@code veinminer-enabled} switch are both on, every
 * connected block of the same material is broken too, up to
 * {@code veinminer-max-blocks}.
 *
 * <p>Adjacency depends on {@code veinminer-diagonal}: when off, only
 * blocks sharing a full face (6-direction, {@link #FACE_OFFSETS}) count
 * as connected; when on (the default), blocks touching only at an edge
 * or corner also count ({@link #ALL_OFFSETS}, 26-direction) — a
 * diagonal-only connection is a common real shape for an ore pocket that
 * would otherwise look like two disconnected veins to a face-only scan.
 *
 * <p>Restricted to pickaxes deliberately — the default material list is
 * entirely ores/ancient debris, all of which require a pickaxe; an admin
 * who adds a non-ore material to the list should be aware veinminer will
 * simply never trigger for it unless mined with a pickaxe.
 *
 * <p>Each extra block is broken via {@link Block#breakNaturally(ItemStack, boolean)}
 * — which does NOT itself fire another {@link BlockBreakEvent}, so
 * there's no reentrancy risk here — with drops/fortune/silk-touch
 * handled the same way a normal break would. {@code breakNaturally}
 * itself never awards experience though (that's normally handled by the
 * vanilla block-break pipeline the *initial*, real event-triggered break
 * goes through, not by a programmatic break like this) — see
 * {@link #rollExperience} for the manual XP roll that closes that gap;
 * without it, only the one block the player actually clicked gave XP,
 * every other block in the vein gave items but silently no experience.
 *
 * <p>Tool durability is tracked manually (see {@link #damageTool}),
 * respecting the Unbreaking enchantment's real probability of avoiding
 * damage; the vein stops early the moment the tool breaks, leaving the
 * rest of the ore for the player to mine normally afterward.
 */
public class VeinMinerListener implements Listener {

    /**
     * Relative (dx, dy, dz) offsets to every face-adjacent neighbor —
     * used when {@code veinminer-diagonal} is off.
     */
    private static final int[][] FACE_OFFSETS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}
    };

    /**
     * All 26 neighboring offsets — every block sharing at least an edge
     * or a corner with the current one, not just a full face. Used when
     * {@code veinminer-diagonal} is on (the default).
     */
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
            // A synthetic event fired below, from this exact listener OR from
            // TreeCapitatorListener, must never be treated as a fresh trigger —
            // otherwise a chain-broken block would start its own nested flood
            // fill from inside this loop.
            if (BulkBreakGuard.isActive()) {
                return;
            }

            if (!config.veinminerEnabled()) {
                return;
            }

            Player player = event.getPlayer();

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

                // A real synthetic BlockBreakEvent per chained block — the only
                // thing that lets a protection plugin, a logging plugin, or
                // Poppy's own DeathChestManager see and potentially deny this
                // specific block, the same way they would for a normal manual
                // break.
                BlockBreakEvent syntheticEvent = new BlockBreakEvent(block, player);
                BulkBreakGuard.run(() -> Bukkit.getPluginManager().callEvent(syntheticEvent));

                if (syntheticEvent.isCancelled()) {
                    // Denied by something — leave this one block standing and
                    // continue with the rest of the vein, exactly as if the
                    // player had tried to break it manually and been blocked.
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

    /**
     * Rolls a random XP amount within {@link #ORE_XP_RANGES}' bounds for
     * this material, or 0 if it's not an ore that awards direct mining
     * XP, or 0 unconditionally under Silk Touch — matching vanilla's own
     * rule that Silk Touch voids ore mining XP.
     */
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

    /**
     * Applies one point of durability damage to {@code tool}, respecting
     * Unbreaking's real probability of absorbing the hit
     * ({@code 1 / (level + 1)} chance to actually take damage). Returns
     * false (and removes the item) if this damage breaks the tool.
     */
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