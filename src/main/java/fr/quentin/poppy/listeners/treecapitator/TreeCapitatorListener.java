package fr.quentin.poppy.listeners.treecapitator;

import fr.quentin.poppy.manager.treecapitator.PlacedLogManager;
import fr.quentin.poppy.manager.treecapitator.TreeCapitatorManager;
import fr.quentin.poppy.util.BulkBreakGuard;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
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
 * their personal toggle (see {@link TreeCapitatorManager}), the live
 * {@code poppy.treecapitator} permission, and the plugin-wide
 * {@code treecapitator-enabled} switch are all satisfied, every
 * connected block of the same log material is broken too, up to
 * {@code treecapitator-max-blocks}.
 *
 * <p><b>Permission is re-checked live on every break</b> — same
 * reasoning as {@code VeinMinerListener} ({@link TreeCapitatorManager}'s
 * per-player override survives a revoked permission node).
 *
 * <p><b>Restricted to Survival</b> — same reasoning as
 * {@code VeinMinerListener}.
 *
 * <p><b>Never chains onto — or through — a player-placed log</b>: see
 * {@link PlacedLogManager}. Without this, felling an unrelated natural
 * tree near a wooden build could accidentally chop the build down too.
 * A placed log breaking directly doesn't trigger the feature at all;
 * one encountered mid-chain acts as a wall the flood fill stops at
 * rather than passing through.
 *
 * <p>Adjacency, the synthetic per-block {@link BlockBreakEvent} (via
 * {@link BulkBreakGuard}), the chunk-load-safe flood fill with packed
 * coordinates, only debiting tool durability for a block that was
 * actually removed, firing {@link PlayerItemDamageEvent}/
 * {@link PlayerItemBreakEvent} plus the break sound, and the manual
 * {@link Statistic#MINE_BLOCK} increment + hunger exhaustion per broken
 * block, all follow exactly the same reasoning as
 * {@code VeinMinerListener} — see its class-level doc for the full
 * explanation of each.
 *
 * <p>No experience is awarded for any of this — unlike ore mining,
 * chopping wood never grants XP in vanilla Minecraft.
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

            if (!player.hasPermission("poppy.treecapitator")) {
                return;
            }

            if (player.getGameMode() != GameMode.SURVIVAL) {
                return;
            }

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

                Material blockMaterial = block.getType();

                boolean broken;
                if (syntheticEvent.isDropItems()) {
                    broken = block.breakNaturally(currentTool, true);
                } else {
                    block.setType(Material.AIR);
                    broken = true;
                }

                if (broken) {
                    player.incrementStatistic(Statistic.MINE_BLOCK, blockMaterial);
                    player.setExhaustion(player.getExhaustion() + 0.005f);
                }

                if (broken && !damageTool(player, currentTool)) {
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in TreeCapitatorListener#onBreak for " + event.getPlayer().getName(), e);
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

                if (placedLogManager.isPlacedByPlayer(neighbor)) {
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

        PlayerItemDamageEvent damageEvent = new PlayerItemDamageEvent(player, tool, 1, damageable.getDamage());
        Bukkit.getPluginManager().callEvent(damageEvent);
        if (damageEvent.isCancelled()) {
            return true;
        }

        int newDamage = damageable.getDamage() + damageEvent.getDamage();
        if (newDamage >= tool.getType().getMaxDurability()) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(player, tool));
            player.getInventory().setItemInMainHand(null);
            return false;
        }

        damageable.setDamage(newDamage);
        tool.setItemMeta(damageable);
        return true;
    }
}