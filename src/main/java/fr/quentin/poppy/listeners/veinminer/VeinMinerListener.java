package fr.quentin.poppy.listeners.veinminer;

import fr.quentin.poppy.manager.veinminer.VeinMinerManager;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Material;
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
 * handled the same way a normal break would. Tool durability is tracked
 * manually (see {@link #damageTool}), respecting the Unbreaking
 * enchantment's real probability of avoiding damage; the vein stops
 * early the moment the tool breaks, leaving the rest of the ore for the
 * player to mine normally afterward.
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
            if (!config.veinminerEnabled()) {
                return;
            }

            Player player = event.getPlayer();
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

                block.breakNaturally(currentTool, true);

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
     * excluded (it's already being broken by the triggering event). Uses
     * {@link #ALL_OFFSETS} or {@link #FACE_OFFSETS} depending on
     * {@code diagonal}.
     */
    private List<Block> findConnectedBlocks(Block origin, Material material, int maxBlocks, boolean diagonal) {
        int[][] offsets = diagonal ? ALL_OFFSETS : FACE_OFFSETS;

        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> result = new ArrayList<>();

        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block current = queue.poll();
            for (int[] offset : offsets) {
                Block neighbor = current.getRelative(offset[0], offset[1], offset[2]);
                if (!visited.add(neighbor)) {
                    continue;
                }
                if (neighbor.getType() != material) {
                    continue;
                }
                result.add(neighbor);
                if (result.size() >= maxBlocks) {
                    break;
                }
                queue.add(neighbor);
            }
        }

        return result;
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