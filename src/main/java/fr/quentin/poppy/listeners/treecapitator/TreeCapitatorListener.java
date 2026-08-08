package fr.quentin.poppy.listeners.treecapitator;

import fr.quentin.poppy.manager.treecapitator.TreeCapitatorManager;
import fr.quentin.poppy.util.BulkBreakGuard;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

    public TreeCapitatorListener(JavaPlugin plugin, PoppyConfig config, TreeCapitatorManager treeCapitatorManager) {
        this.plugin = plugin;
        this.config = config;
        this.treeCapitatorManager = treeCapitatorManager;
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