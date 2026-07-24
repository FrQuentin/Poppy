package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class DeathChestManager implements Listener {

    private static final int SEARCH_RADIUS = 5;
    private static final BlockFace CHEST_FACING = BlockFace.SOUTH;
    private static final BlockFace LEFT_DIRECTION = BlockFace.WEST;
    private static final BlockFace RIGHT_DIRECTION = BlockFace.EAST;
    private static final BlockFace[] EXTEND_FACES = {RIGHT_DIRECTION, LEFT_DIRECTION};

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final NamespacedKey ownerKey;
    private final NamespacedKey createdAtKey;
    private final NamespacedKey xpAmountKey;

    public DeathChestManager(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
        this.ownerKey = new NamespacedKey(plugin, "death_chest_owner");
        this.createdAtKey = new NamespacedKey(plugin, "death_chest_created");
        this.xpAmountKey = new NamespacedKey(plugin, "death_chest_xp_amount");

        scanAlreadyLoadedChunks();
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            Player player = event.getEntity();
            List<ItemStack> drops = new ArrayList<>(event.getDrops());

            if (config.deathChestStoreXp()) {
                int levelAtDeath = player.getLevel();
                int totalXp = getTotalExperience(player, levelAtDeath);
                if (totalXp > 0) {
                    drops.add(createXpBottle(levelAtDeath, totalXp));
                    event.setDroppedExp(0);
                }
            }

            if (drops.isEmpty()) {
                return;
            }

            Location primary = findPlacementSpot(player.getLocation());
            if (primary == null) {
                return;
            }

            Location secondary = drops.size() > 27 ? findAdjacentSpot(primary) : null;

            event.getDrops().clear();

            if (secondary != null) {
                BlockFace direction = directionBetween(primary, secondary);
                Type primaryType = direction == LEFT_DIRECTION ? Type.LEFT : Type.RIGHT;
                Type secondaryType = primaryType == Type.LEFT ? Type.RIGHT : Type.LEFT;
                placeChest(primary, player, primaryType);
                placeChest(secondary, player, secondaryType);
            } else {
                placeChest(primary, player, Type.SINGLE);
            }

            Inventory inventory = ((Chest) primary.getBlock().getState()).getInventory();
            List<ItemStack> overflow = new ArrayList<>();
            for (ItemStack item : drops) {
                overflow.addAll(inventory.addItem(item).values());
            }

            for (ItemStack item : overflow) {
                player.getWorld().dropItemNaturally(primary, item);
            }

            logger.log(PoppyLogger.Category.DEATH_CHEST, player, "death chest created at "
                    + primary.getWorld().getName() + ": " + primary.getBlockX() + ", " + primary.getBlockY() + ", " + primary.getBlockZ()
                    + " (" + drops.size() + " items" + (secondary != null ? ", double chest" : "") + ")");

            player.sendMessage(messages.get("death.chest-created",
                    "x", String.valueOf(primary.getBlockX()),
                    "y", String.valueOf(primary.getBlockY()),
                    "z", String.valueOf(primary.getBlockZ())));

            long expiryMillis = config.deathChestExpiryMillis();
            if (expiryMillis > 0) {
                UUID ownerUuid = player.getUniqueId();
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> expireChest(primary, secondary, ownerUuid),
                        expiryMillis / 50L);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error creating death chest for " + event.getEntity().getName(), e);
        }
    }

    @EventHandler
    public void onOpen(@NonNull InventoryOpenEvent event) {
        if (!config.deathChestEnabled() || !config.deathChestProtect()) {
            return;
        }

        try {
            Chest chest = resolveChest(event.getInventory().getHolder());
            if (chest == null) {
                return;
            }

            String ownerString = chest.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerString == null) {
                return;
            }

            if (!(event.getPlayer() instanceof Player opener)) {
                return;
            }

            boolean isOwner = ownerString.equals(opener.getUniqueId().toString());
            boolean bypass = opener.hasPermission("poppy.deathchest.bypass");

            if (!isOwner && !bypass) {
                event.setCancelled(true);
                logger.log(PoppyLogger.Category.DEATH_CHEST, opener,
                        "attempted to open a death chest owned by " + ownerName(ownerString) + " (denied)");
                opener.sendMessage(messages.get("death.chest-not-yours"));
            } else if (!isOwner) {
                logger.log(PoppyLogger.Category.ADMIN, opener,
                        "opened a death chest owned by " + ownerName(ownerString) + " using bypass permission");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onOpen", e);
        }
    }

    @EventHandler
    public void onClose(@NonNull InventoryCloseEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            InventoryHolder holder = event.getInventory().getHolder();
            Chest chest = resolveChest(holder);
            if (chest == null) {
                return;
            }

            String ownerString = chest.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerString == null) {
                return;
            }

            if (isEmpty(event.getInventory())) {
                removeChestBlocks(holder);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onClose", e);
        }
    }

    @EventHandler
    public void onBreak(@NonNull BlockBreakEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            Block block = event.getBlock();
            if (block.getType() != Material.CHEST) {
                return;
            }
            if (!(block.getState() instanceof Chest chestState)) {
                return;
            }

            String ownerString = chestState.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerString == null) {
                return;
            }

            Player breaker = event.getPlayer();
            if (breaker.hasPermission("poppy.deathchest.bypass")) {
                logger.log(PoppyLogger.Category.ADMIN, breaker,
                        "broke a death chest owned by " + ownerName(ownerString) + " using bypass permission");
                return;
            }

            event.setCancelled(true);
            breaker.sendMessage(messages.get("death.chest-unbreakable"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onBreak", e);
        }
    }

    @EventHandler
    public void onItemMove(@NonNull InventoryMoveItemEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            if (isDeathChest(event.getSource()) || isDeathChest(event.getDestination())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onItemMove", e);
        }
    }

    private boolean isDeathChest(Inventory inventory) {
        Chest chest = resolveChest(inventory.getHolder());
        if (chest == null) {
            return false;
        }
        return chest.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING) != null;
    }

    @EventHandler
    public void onXpBottleUse(@NonNull PlayerInteractEvent event) {
        if (!config.deathChestEnabled() || !config.deathChestStoreXp()) {
            return;
        }

        try {
            if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            ItemStack item = event.getItem();
            if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE) {
                return;
            }

            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return;
            }

            int storedXp = meta.getPersistentDataContainer().getOrDefault(xpAmountKey, PersistentDataType.INTEGER, 0);
            if (storedXp <= 0) {
                return;
            }

            event.setCancelled(true);

            Player player = event.getPlayer();
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            player.giveExp(storedXp);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            player.sendMessage(messages.get("death.xp-restored"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onXpBottleUse for " + event.getPlayer().getName(), e);
        }
    }

    private void scanAlreadyLoadedChunks() {
        if (config.deathChestExpiryMillis() <= 0) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForExpiredChests(chunk);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(@NonNull ChunkLoadEvent event) {
        if (!config.deathChestEnabled() || config.deathChestExpiryMillis() <= 0) {
            return;
        }

        try {
            scanChunkForExpiredChests(event.getChunk());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error scanning chunk for expired death chests", e);
        }
    }

    private void scanChunkForExpiredChests(Chunk chunk) {
        long expiryMillis = config.deathChestExpiryMillis();

        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Chest chestState)) {
                continue;
            }

            String ownerString = chestState.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerString == null) {
                continue;
            }

            long createdAt = chestState.getPersistentDataContainer().getOrDefault(createdAtKey, PersistentDataType.LONG, 0L);
            if (System.currentTimeMillis() - createdAt < expiryMillis) {
                continue;
            }

            try {
                UUID owner = UUID.fromString(ownerString);
                boolean droppedAnything = dropRemainingAndClear(chestState.getLocation(), owner);
                if (droppedAnything) {
                    logger.log(PoppyLogger.Category.DEATH_CHEST, ownerName(ownerString),
                            "death chest expired (recovered after a restart), remaining items dropped on the ground");
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Death chest at " + chestState.getLocation() + " had an invalid owner UUID: " + ownerString);
            }
        }
    }

    private ItemStack createXpBottle(int levelAtDeath, int totalXp) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = bottle.getItemMeta();

        meta.displayName(messages.get("death.xp-bottle-name", "levels", String.valueOf(levelAtDeath)));
        meta.lore(List.of(messages.get("death.xp-bottle-lore", "levels", String.valueOf(levelAtDeath))));
        meta.getPersistentDataContainer().set(xpAmountKey, PersistentDataType.INTEGER, totalXp);

        bottle.setItemMeta(meta);
        return bottle;
    }

    private int getTotalExperience(Player player, int level) {
        return getExpAtLevel(level) + Math.round(player.getExp() * getExpToNextLevel(level));
    }

    private int getExpAtLevel(int level) {
        if (level <= 15) {
            return (level * level) + (6 * level);
        } else if (level <= 30) {
            return (int) ((2.5 * level * level) - (40.5 * level) + 360);
        } else {
            return (int) ((4.5 * level * level) - (162.5 * level) + 2220);
        }
    }

    private int getExpToNextLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }

    private boolean isEmpty(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    private void removeChestBlocks(InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            chest.getBlock().setType(Material.AIR);
        } else if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof Chest left) {
                left.getBlock().setType(Material.AIR);
            }
            if (doubleChest.getRightSide() instanceof Chest right) {
                right.getBlock().setType(Material.AIR);
            }
        }
    }

    private Chest resolveChest(InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            return chest;
        }
        if (holder instanceof DoubleChest doubleChest && doubleChest.getLeftSide() instanceof Chest chest) {
            return chest;
        }
        return null;
    }

    private void placeChest(Location location, Player owner, Type chestType) {
        Block block = location.getBlock();
        block.setType(Material.CHEST);

        BlockData rawData = block.getBlockData();
        if (rawData instanceof org.bukkit.block.data.type.Chest chestData) {
            chestData.setFacing(CHEST_FACING);
            chestData.setType(chestType);
            block.setBlockData(chestData);
        }

        Chest chestState = (Chest) block.getState();
        chestState.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        chestState.getPersistentDataContainer().set(createdAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        chestState.update(true, false);
    }

    private void expireChest(Location primary, Location secondary, UUID owner) {
        try {
            boolean droppedAnything = dropRemainingAndClear(primary, owner);
            droppedAnything |= secondary != null && dropRemainingAndClear(secondary, owner);

            if (droppedAnything) {
                logger.log(PoppyLogger.Category.DEATH_CHEST, ownerName(owner.toString()),
                        "death chest expired, remaining items dropped on the ground");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error expiring a death chest for " + owner, e);
        }
    }

    private boolean dropRemainingAndClear(Location location, UUID owner) {
        Block block = location.getBlock();
        if (block.getType() != Material.CHEST) {
            return false;
        }

        Chest chestState = (Chest) block.getState();
        String ownerString = chestState.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerString == null || !ownerString.equals(owner.toString())) {
            return false;
        }

        boolean droppedAnything = false;
        for (ItemStack item : chestState.getBlockInventory().getContents()) {
            if (item != null) {
                location.getWorld().dropItemNaturally(location, item);
                droppedAnything = true;
            }
        }

        block.setType(Material.AIR);
        return droppedAnything;
    }

    private Location findPlacementSpot(Location deathLocation) {
        if (isPlaceable(deathLocation)) {
            return centered(deathLocation);
        }

        Location best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Location candidate = deathLocation.clone().add(dx, dy, dz);
                    if (!isPlaceable(candidate)) {
                        continue;
                    }
                    double distanceSquared = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = candidate;
                    }
                }
            }
        }

        return best == null ? null : centered(best);
    }

    private Location findAdjacentSpot(Location primary) {
        for (BlockFace face : EXTEND_FACES) {
            Location candidate = primary.clone().add(face.getDirection());
            if (isPlaceable(candidate)) {
                return centered(candidate);
            }
        }
        return null;
    }

    private BlockFace directionBetween(Location from, Location to) {
        if (to.getBlockX() > from.getBlockX()) {
            return BlockFace.EAST;
        }
        if (to.getBlockX() < from.getBlockX()) {
            return BlockFace.WEST;
        }
        return BlockFace.SOUTH;
    }

    private boolean isPlaceable(Location location) {
        World world = location.getWorld();
        if (location.getY() < world.getMinHeight() || location.getY() > world.getMaxHeight()) {
            return false;
        }

        Block block = location.getBlock();
        if (!block.getBlockData().isReplaceable()) {
            return false;
        }

        Material type = block.getType();
        return type != Material.LAVA && type != Material.FIRE && type != Material.SOUL_FIRE;
    }

    private Location centered(Location location) {
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private String ownerName(String ownerUuidString) {
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(ownerUuidString));
            String name = offlinePlayer.getName();
            return name != null ? name : ownerUuidString;
        } catch (IllegalArgumentException e) {
            return ownerUuidString;
        }
    }
}