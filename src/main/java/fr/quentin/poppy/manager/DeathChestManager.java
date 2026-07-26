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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * On death, stores the player's dropped items — and, if enabled, a bottle
 * holding their lost XP — into a protected virtual chest, and places a
 * single chest block near the death location as a purely cosmetic access
 * point for it. Toggleable via {@code death-chest-enabled} in config.yml.
 *
 * <p><b>Storage model:</b> the loot never lives in the placed block's own
 * native inventory ({@link org.bukkit.block.Chest#getBlockInventory()}).
 * It lives in a standalone {@link Inventory} created via
 * {@link Bukkit#createInventory(InventoryHolder, int, net.kyori.adventure.text.Component)}
 * with a custom {@link DeathChestHolder}, tracked in {@link #chests} and
 * persisted to {@code deathchests.yml}. The block's real inventory is
 * never written to; right-clicking it (see {@link #onInteract}) is denied
 * at the vanilla level and Poppy opens the virtual inventory itself
 * instead. This makes explosions, pistons, and hoppers structurally
 * unable to extract or duplicate anything: there's nothing in the real
 * container to act on.
 *
 * <p><b>Always a single block, never a double chest:</b> the virtual
 * inventory is a fixed {@link #VIRTUAL_INVENTORY_SIZE}-slot container
 * ({@value #VIRTUAL_INVENTORY_SIZE} slots) regardless of item count — a
 * double chest was only ever needed in the old design to get past a real
 * single chest's 27-slot cap. Since the placed block is now just a
 * clickable marker and the actual storage is fully virtual, one block is
 * enough to open the whole 54-slot inventory no matter how many items are
 * in it. This also halves the chance of {@link #findPlacementSpot}
 * failing to find room (no second, adjacent spot ever needs finding), and
 * removes an entire class of double-chest-specific edge cases (piston
 * pushing one half, protection plugins treating the two halves
 * differently, etc.).
 *
 * <p>If either the {@code keepInventory} or {@code keepLevel} gamerule is
 * on, {@link #onDeath} bails out immediately.
 *
 * <p>Persistence is a single file written synchronously via
 * {@link #writeAtomically} — a temp-file-then-atomic-rename, same
 * technique as {@link HomeManager#writeToDisk}. Loaded once at startup in
 * the constructor; a chest whose world isn't currently loaded is skipped
 * with a warning. An already-expired chest at startup is not force-loaded
 * — {@link #scanAlreadyLoadedChunks} and {@link #onChunkLoad} catch it
 * once its chunk actually loads.
 *
 * <p>{@link #removeChest} is guarded against reentrancy via
 * {@link #chestsBeingRemoved}: force-closing every viewer (including the
 * player who triggered {@link #onClose} in the first place) fires a new
 * {@link InventoryCloseEvent} synchronously for that same player, which
 * without this guard would re-enter {@link #onClose} → {@link #removeChest}
 * → close viewers → ... infinitely, overflowing the stack and crashing the
 * server. The guard makes the re-entrant call a no-op.
 *
 * <p>XP refund is a percentage of the player's total XP —
 * {@code death-chest-xp-refund-percent} in config.yml, 50 by default.
 * Each XP bottle is bound to the player it was created for, closing off
 * using it to transfer XP between accounts.
 *
 * <p>Placement is guarded by a simulated {@link BlockPlaceEvent} (see
 * {@link #isProtected}) so protection plugins get a chance to refuse the
 * block appearing inside a claim.
 *
 * <p>Explosions and pistons are still prevented from destroying/moving a
 * death chest block — not for anti-duplication (structurally moot now),
 * but to avoid orphaning the owner's access to their still-safe stored
 * loot. {@link #onBreak} still blocks mining it (anti-farm), running at
 * {@link EventPriority#HIGHEST} with {@code ignoreCancelled = true} so a
 * protection plugin's later cancellation is always visible first.
 */
public class DeathChestManager implements Listener {

    private static final int SEARCH_RADIUS = 5;
    private static final int VIRTUAL_INVENTORY_SIZE = 54;
    private static final BlockFace CHEST_FACING = BlockFace.SOUTH;

    /**
     * Marker holder identifying a death chest's virtual inventory, purely
     * to recover the chest's ID in {@link #onClose}.
     */
    private static final class DeathChestHolder implements InventoryHolder {
        private final UUID chestId;
        private Inventory inventory;

        DeathChestHolder(UUID chestId) {
            this.chestId = chestId;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NonNull Inventory getInventory() {
            return inventory;
        }

        UUID getChestId() {
            return chestId;
        }
    }

    /**
         * One active death chest: its owner, the single block location that
         * serves as its cosmetic access point, and the live virtual
         * {@link Inventory} that actually holds the loot.
         */
        private record ChestData(UUID id, UUID owner, Location location, long createdAt, Inventory inventory) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final NamespacedKey chestIdKey;
    private final NamespacedKey xpAmountKey;
    private final NamespacedKey xpBottleOwnerKey;

    private final Map<UUID, ChestData> chests = new HashMap<>();
    private final Set<UUID> chestsBeingRemoved = new HashSet<>();

    public DeathChestManager(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
        this.chestIdKey = new NamespacedKey(plugin, "death_chest_id");
        this.xpAmountKey = new NamespacedKey(plugin, "death_chest_xp_amount");
        this.xpBottleOwnerKey = new NamespacedKey(plugin, "death_chest_xp_bottle_owner");

        loadAll();
        scanAlreadyLoadedChunks();
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        if (event.getKeepInventory() || event.getKeepLevel()) {
            return;
        }

        try {
            Player player = event.getEntity();

            long refundXp = 0;
            int levelAtDeath = player.getLevel();
            if (config.deathChestStoreXp()) {
                long totalXp = getTotalExperience(player, levelAtDeath);
                refundXp = (totalXp * config.deathChestXpRefundPercent()) / 100;
            }

            List<ItemStack> plannedDrops = new ArrayList<>(event.getDrops());
            if (refundXp > 0) {
                plannedDrops.add(createXpBottle(player, levelAtDeath, refundXp));
            }

            if (plannedDrops.isEmpty()) {
                return;
            }

            Location spot = findPlacementSpot(player.getLocation(), player);
            if (spot == null) {
                // No safe/unprotected spot found nearby: leave everything on the vanilla
                // path untouched.
                return;
            }

            event.getDrops().clear();
            if (refundXp > 0) {
                event.setDroppedExp(0);
            }

            UUID id = UUID.randomUUID();
            placeChestBlock(spot, id);

            ChestData data = createChestData(id, player.getUniqueId(), spot, System.currentTimeMillis(), List.of());
            chests.put(id, data);

            List<ItemStack> overflow = new ArrayList<>();
            for (ItemStack item : plannedDrops) {
                overflow.addAll(data.inventory.addItem(item).values());
            }
            for (ItemStack item : overflow) {
                player.getWorld().dropItemNaturally(spot, item);
            }

            persistAll();

            logger.log(PoppyLogger.Category.DEATH_CHEST, player, "death chest created at "
                    + spot.getWorld().getName() + ": " + spot.getBlockX() + ", " + spot.getBlockY() + ", " + spot.getBlockZ()
                    + " (" + plannedDrops.size() + " items)");

            player.sendMessage(messages.get("death.chest-created",
                    "x", String.valueOf(spot.getBlockX()),
                    "y", String.valueOf(spot.getBlockY()),
                    "z", String.valueOf(spot.getBlockZ())));

            long expiryMillis = config.deathChestExpiryMillis();
            if (expiryMillis > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> expireIfPresent(id), expiryMillis / 50L);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error creating death chest for " + event.getEntity().getName(), e);
        }
    }

    /**
     * Intercepts right-clicking a death chest block entirely — vanilla's
     * own chest-open behavior is denied so its (always-empty) real
     * inventory is never shown even briefly; ownership is checked here,
     * then the actual virtual inventory is opened directly.
     */
    @EventHandler
    public void onInteract(@NonNull PlayerInteractEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            Block block = event.getClickedBlock();
            if (block == null) {
                return;
            }

            UUID id = chestIdOf(block);
            if (id == null) {
                return;
            }

            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);

            Player player = event.getPlayer();
            ChestData data = chests.get(id);
            if (data == null) {
                player.sendMessage(messages.get("death.chest-not-yours"));
                return;
            }

            boolean isOwner = data.owner.equals(player.getUniqueId());
            boolean bypass = player.hasPermission("poppy.deathchest.bypass");

            if (config.deathChestProtect() && !isOwner && !bypass) {
                logger.log(PoppyLogger.Category.DEATH_CHEST, player,
                        "attempted to open a death chest owned by " + ownerNameOf(data.owner) + " (denied)");
                player.sendMessage(messages.get("death.chest-not-yours"));
                return;
            }

            if (!isOwner) {
                logger.log(PoppyLogger.Category.ADMIN, player,
                        "opened a death chest owned by " + ownerNameOf(data.owner) + " using bypass permission");
            }

            player.openInventory(data.inventory);
            // Right-click is intercepted (setUseInteractedBlock DENY above), so vanilla's
            // own chest-open sound never fires — this replaces it manually. Played via
            // the world (not just to the opener), matching how a real chest's sound is
            // audible to nearby players too.
            data.location.getWorld().playSound(data.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onInteract for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onClose(@NonNull InventoryCloseEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            if (!(event.getInventory().getHolder() instanceof DeathChestHolder holder)) {
                return;
            }

            ChestData data = chests.get(holder.getChestId());
            if (data == null) {
                return;
            }

            // Same reasoning as the open sound in onInteract: this inventory was never
            // vanilla-opened, so there's no automatic close sound either — played here
            // regardless of whether the chest is about to despawn or just closed with
            // items still inside.
            data.location.getWorld().playSound(data.location, Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);

            if (isEmpty(data.inventory)) {
                removeChest(data, false);
            } else {
                persistAll();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onClose", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(@NonNull BlockBreakEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }

        try {
            UUID id = chestIdOf(event.getBlock());
            if (id == null) {
                return;
            }

            Player breaker = event.getPlayer();
            ChestData data = chests.get(id);

            if (data == null) {
                if (!breaker.hasPermission("poppy.deathchest.bypass")) {
                    event.setCancelled(true);
                    breaker.sendMessage(messages.get("death.chest-unbreakable"));
                }
                return;
            }

            if (breaker.hasPermission("poppy.deathchest.bypass")) {
                logger.log(PoppyLogger.Category.ADMIN, breaker,
                        "broke a death chest owned by " + ownerNameOf(data.owner) + " using bypass permission");
                event.setCancelled(true);
                removeChest(data, true);
                return;
            }

            event.setCancelled(true);
            breaker.sendMessage(messages.get("death.chest-unbreakable"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onBreak", e);
        }
    }

    @EventHandler
    public void onEntityExplode(@NonNull EntityExplodeEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }
        try {
            event.blockList().removeIf(block -> chestIdOf(block) != null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onEntityExplode", e);
        }
    }

    @EventHandler
    public void onBlockExplode(@NonNull BlockExplodeEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }
        try {
            event.blockList().removeIf(block -> chestIdOf(block) != null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onBlockExplode", e);
        }
    }

    @EventHandler
    public void onPistonExtend(@NonNull BlockPistonExtendEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }
        try {
            if (event.getBlocks().stream().anyMatch(block -> chestIdOf(block) != null)) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onPistonExtend", e);
        }
    }

    @EventHandler
    public void onPistonRetract(@NonNull BlockPistonRetractEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }
        try {
            if (event.getBlocks().stream().anyMatch(block -> chestIdOf(block) != null)) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onPistonRetract", e);
        }
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

            long storedXp = meta.getPersistentDataContainer().getOrDefault(xpAmountKey, PersistentDataType.LONG, 0L);
            if (storedXp <= 0) {
                return;
            }

            Player player = event.getPlayer();

            String boundOwner = meta.getPersistentDataContainer().get(xpBottleOwnerKey, PersistentDataType.STRING);
            if (boundOwner != null && !boundOwner.equals(player.getUniqueId().toString())) {
                event.setCancelled(true);
                player.sendMessage(messages.get("death.xp-bottle-not-yours"));
                return;
            }

            event.setCancelled(true);

            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            int xpToGive = (int) Math.min(storedXp, Integer.MAX_VALUE);
            player.giveExp(xpToGive);
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
        for (ChestData data : new ArrayList<>(chests.values())) {
            checkExpiryIfChunkLoaded(data);
        }
    }

    @EventHandler
    public void onChunkLoad(@NonNull ChunkLoadEvent event) {
        if (!config.deathChestEnabled() || config.deathChestExpiryMillis() <= 0) {
            return;
        }
        try {
            Chunk chunk = event.getChunk();
            for (ChestData data : new ArrayList<>(chests.values())) {
                if (locationInChunk(data.location, chunk)) {
                    checkExpiry(data);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error scanning chunk for expired death chests", e);
        }
    }

    private void checkExpiryIfChunkLoaded(ChestData data) {
        World world = data.location.getWorld();
        if (world == null) {
            return;
        }
        if (!world.isChunkLoaded(data.location.getBlockX() >> 4, data.location.getBlockZ() >> 4)) {
            return;
        }
        checkExpiry(data);
    }

    private void checkExpiry(ChestData data) {
        if (System.currentTimeMillis() - data.createdAt < config.deathChestExpiryMillis()) {
            return;
        }
        expireIfPresent(data.id);
    }

    private boolean locationInChunk(Location location, Chunk chunk) {
        return location.getWorld().equals(chunk.getWorld())
                && (location.getBlockX() >> 4) == chunk.getX()
                && (location.getBlockZ() >> 4) == chunk.getZ();
    }

    private void expireIfPresent(UUID id) {
        try {
            ChestData data = chests.get(id);
            if (data == null) {
                return;
            }

            boolean hadItems = !isEmpty(data.inventory);
            removeChest(data, true);

            if (hadItems) {
                logger.log(PoppyLogger.Category.DEATH_CHEST, ownerNameOf(data.owner),
                        "death chest expired, remaining items dropped on the ground");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error expiring a death chest", e);
        }
    }

    private ItemStack createXpBottle(Player owner, int levelAtDeath, long totalXp) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = bottle.getItemMeta();

        meta.displayName(messages.get("death.xp-bottle-name", "levels", String.valueOf(levelAtDeath)));
        meta.lore(List.of(messages.get("death.xp-bottle-lore", "levels", String.valueOf(levelAtDeath))));
        meta.getPersistentDataContainer().set(xpAmountKey, PersistentDataType.LONG, totalXp);
        meta.getPersistentDataContainer().set(xpBottleOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());

        bottle.setItemMeta(meta);
        return bottle;
    }

    private long getTotalExperience(Player player, int level) {
        return getExpAtLevel(level) + Math.round(player.getExp() * getExpToNextLevel(level));
    }

    private long getExpAtLevel(int level) {
        if (level <= 15) {
            return (long) level * level + (6L * level);
        } else if (level <= 30) {
            return Math.round((2.5 * level * level) - (40.5 * level) + 360);
        } else {
            return Math.round((4.5 * level * level) - (162.5 * level) + 2220);
        }
    }

    private long getExpToNextLevel(int level) {
        if (level <= 15) {
            return 2L * level + 7;
        } else if (level <= 30) {
            return 5L * level - 38;
        } else {
            return 9L * level - 158;
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

    /**
     * Guarded against reentrancy — see the class-level doc.
     */
    private void removeChest(ChestData data, boolean dropRemaining) {
        if (!chestsBeingRemoved.add(data.id)) {
            return;
        }

        try {
            new ArrayList<>(data.inventory.getViewers()).forEach(HumanEntity::closeInventory);

            if (dropRemaining) {
                for (ItemStack item : data.inventory.getContents()) {
                    if (item != null) {
                        data.location.getWorld().dropItemNaturally(data.location, item);
                    }
                }
            }

            removeBlockIfMatching(data.location, data.id);

            chests.remove(data.id);
            persistAll();
        } finally {
            chestsBeingRemoved.remove(data.id);
        }
    }

    private void removeBlockIfMatching(Location location, UUID expectedId) {
        Block block = location.getBlock();
        if (expectedId.equals(chestIdOf(block))) {
            block.setType(Material.AIR);
        }
    }

    private ChestData createChestData(UUID id, UUID owner, Location location, long createdAt, List<ItemStack> initialItems) {
        DeathChestHolder holder = new DeathChestHolder(id);
        Inventory inventory = Bukkit.createInventory(holder, VIRTUAL_INVENTORY_SIZE,
                messages.get("death.chest-gui-title", "player", ownerNameOf(owner)));
        holder.setInventory(inventory);

        for (ItemStack item : initialItems) {
            inventory.addItem(item);
        }

        return new ChestData(id, owner, location, createdAt, inventory);
    }

    /**
     * Always places a single chest — see the class-level doc for why a
     * double chest is never needed anymore.
     */
    private void placeChestBlock(Location location, UUID id) {
        Block block = location.getBlock();
        block.setType(Material.CHEST);

        BlockData rawData = block.getBlockData();
        if (rawData instanceof org.bukkit.block.data.type.Chest chestData) {
            chestData.setFacing(CHEST_FACING);
            chestData.setType(Type.SINGLE);
            block.setBlockData(chestData);
        }

        Chest chestState = (Chest) block.getState();
        chestState.getPersistentDataContainer().set(chestIdKey, PersistentDataType.STRING, id.toString());
        chestState.update(true, false);
    }

    private UUID chestIdOf(Block block) {
        if (block.getType() != Material.CHEST) {
            return null;
        }
        if (!(block.getState() instanceof Chest chestState)) {
            return null;
        }
        String idString = chestState.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (idString == null) {
            return null;
        }
        try {
            return UUID.fromString(idString);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Location findPlacementSpot(Location deathLocation, Player owner) {
        if (isPlaceable(deathLocation, owner)) {
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
                    if (!isPlaceable(candidate, owner)) {
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

    private boolean isPlaceable(Location location, Player owner) {
        World world = location.getWorld();
        if (location.getY() < world.getMinHeight() || location.getY() > world.getMaxHeight()) {
            return false;
        }

        Block block = location.getBlock();
        if (!block.getBlockData().isReplaceable()) {
            return false;
        }

        Material type = block.getType();
        if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE) {
            return false;
        }

        return !isProtected(location, owner);
    }

    /**
     * Fires a simulated {@link BlockPlaceEvent} before any world mutation
     * happens, so protection plugins get a chance to refuse it. The
     * constructor used here is marked {@code @ApiStatus.Internal} by
     * Paper — there's no stable public alternative for simulating a place
     * event from plugin code, and this is the same technique many
     * protection-adjacent plugins rely on. The risk is a future Paper
     * version changing this signature without a compatibility guarantee;
     * accepted here since verifying claims/protection before a cosmetic
     * block placement matters more than that risk.
     */
    @SuppressWarnings("UnstableApiUsage")
    private boolean isProtected(Location location, Player owner) {
        Block block = location.getBlock();
        BlockState replacedState = block.getState();
        Block placedAgainst = block.getRelative(BlockFace.DOWN);
        ItemStack chestItem = new ItemStack(Material.CHEST);

        BlockPlaceEvent placeEvent = new BlockPlaceEvent(block, replacedState, placedAgainst, chestItem, owner, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(placeEvent);

        return placeEvent.isCancelled() || !placeEvent.canBuild();
    }

    private Location centered(Location location) {
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private String ownerNameOf(UUID uuid) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String name = offlinePlayer.getName();
        return name != null ? name : uuid.toString();
    }

    private File storageFile() {
        return new File(plugin.getDataFolder(), "deathchests.yml");
    }

    private void loadAll() {
        File file = storageFile();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("chests");
        if (section == null) {
            return;
        }

        for (String idString : section.getKeys(false)) {
            try {
                ConfigurationSection cs = section.getConfigurationSection(idString);
                if (cs == null) {
                    continue;
                }

                UUID id = UUID.fromString(idString);
                UUID owner = UUID.fromString(cs.getString("owner", ""));

                String worldName = cs.getString("world", "");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("Skipping death chest " + idString + ": world '" + worldName + "' not found");
                    continue;
                }

                Location location = new Location(world, cs.getInt("x"), cs.getInt("y"), cs.getInt("z"));
                long createdAt = cs.getLong("created");

                List<ItemStack> items = new ArrayList<>();
                List<?> rawItems = cs.getList("items");
                if (rawItems != null) {
                    for (Object o : rawItems) {
                        if (o instanceof ItemStack stack) {
                            items.add(stack);
                        }
                    }
                }

                ChestData data = createChestData(id, owner, location, createdAt, items);
                chests.put(id, data);

                long expiryMillis = config.deathChestExpiryMillis();
                if (expiryMillis > 0) {
                    long remaining = expiryMillis - (System.currentTimeMillis() - createdAt);
                    if (remaining > 0) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> expireIfPresent(id), remaining / 50L);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Skipping a corrupt death chest entry (" + idString + ")", e);
            }
        }
    }

    private void persistAll() {
        YamlConfiguration yaml = new YamlConfiguration();

        for (ChestData data : chests.values()) {
            String base = "chests." + data.id;
            yaml.set(base + ".owner", data.owner.toString());
            yaml.set(base + ".world", data.location.getWorld().getName());
            yaml.set(base + ".x", data.location.getBlockX());
            yaml.set(base + ".y", data.location.getBlockY());
            yaml.set(base + ".z", data.location.getBlockZ());
            yaml.set(base + ".created", data.createdAt);

            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : data.inventory.getContents()) {
                if (item != null) {
                    items.add(item);
                }
            }
            yaml.set(base + ".items", items);
        }

        writeAtomically(yaml, storageFile());
    }

    private void writeAtomically(YamlConfiguration yaml, File file) {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");

        try {
            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save death chests", e);
            if (!tempFile.delete()) {
                plugin.getLogger().log(Level.WARNING, "Could not delete leftover temp file: " + tempFile);
            }
        }
    }

    /**
     * Force-closes every currently open death chest viewer and does a final
     * {@link #persistAll()} — must be called from {@code Poppy#onDisable}
     * before the plugin fully unloads. Without this, a chest whose virtual
     * inventory was open at shutdown time (a player mid-withdrawal, or a
     * {@code /reload}) would have any changes since the last
     * {@link #onClose}/{@link #onDeath} never written to disk — the file and
     * the in-memory state would silently desync, and those changes would be
     * lost on the next load.
     */
    public void shutdown() {
        for (ChestData data : chests.values()) {
            new ArrayList<>(data.inventory().getViewers()).forEach(HumanEntity::closeInventory);
        }
        persistAll();
    }
}