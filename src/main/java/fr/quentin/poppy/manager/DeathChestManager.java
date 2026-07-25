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
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
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

/**
 * On death, places the player's dropped items — and, if enabled, a bottle
 * holding their lost XP — into a protected chest (a double chest if there
 * are more than 27 items) at or near the death location, instead of
 * scattering them on the ground. Toggleable via {@code death-chest-enabled}
 * in config.yml. If either the {@code keepInventory} or {@code keepLevel}
 * gamerule is on, {@link #onDeath} bails out immediately — the player
 * already keeps everything vanilla-side, so there's nothing to compensate
 * for. This check is explicit rather than relying on
 * {@link PlayerDeathEvent#getDrops()} being empty: {@code keepLevel} has
 * no effect on that list at all, so without the explicit check, an XP
 * refund bottle would still get created (and the death chest with it)
 * even though the player never actually lost any XP.
 *
 * <p>Ownership and creation time are stored directly on the chest block's
 * {@link org.bukkit.persistence.PersistentDataContainer} (chests are tile
 * entities, so this survives a server restart without any extra file).
 * The scheduled expiry task from {@link #onDeath} does <b>not</b> survive a
 * restart, though — the constructor and {@link #onChunkLoad} cover that
 * gap: every already-loaded chunk is scanned at startup, and every chunk
 * is scanned again as it loads, so an expired chest is caught (and its
 * remaining contents dropped) even if the server was off past its expiry
 * time.
 *
 * <p>XP refund is a percentage of the player's total XP —
 * {@code death-chest-xp-refund-percent} in config.yml, 50 by default (a
 * deliberate middle ground: 100 would fully negate the death XP penalty,
 * which combined with items also surviving death makes dying nearly
 * consequence-free on a survival server; 0 would mean losing the bottle
 * doesn't even buy back any of it). XP totals are computed and stored as
 * {@code long}, not {@code int} — a player at a very high level (the
 * quadratic XP-per-level formula grows fast) could otherwise silently
 * overflow {@link Integer#MAX_VALUE}. {@link Player#giveExp(int)} only
 * accepts an {@code int}, so the stored {@code long} is clamped to that
 * range only at the point of actually giving it back (see
 * {@link #onXpBottleUse}), not anywhere the value is computed or stored.
 *
 * <p>Each XP bottle is bound to the player it was created for via
 * {@code xpBottleOwnerKey} — redeemable only by them, even if the item is
 * traded, dropped, or otherwise ends up in someone else's inventory,
 * closing off using it as a way to transfer XP between accounts.
 *
 * <p>Placement is guarded by a simulated {@link BlockPlaceEvent} (see
 * {@link #isProtected}) before any world mutation happens — without this,
 * {@code block.setType(...)} bypasses protection plugins (WorldGuard,
 * GriefPrevention, Lands...) entirely, since it mutates the world at the
 * engine level with no event involved, letting a death chest appear
 * inside someone else's claim.
 *
 * <p>Explosions ({@link EntityExplodeEvent}, {@link BlockExplodeEvent})
 * and pistons ({@link BlockPistonExtendEvent}, {@link BlockPistonRetractEvent})
 * are also blocked from affecting a death chest — both destroy or move
 * the block without ever firing {@link BlockBreakEvent}. This event-by-event
 * protection model is inherently fragile — any future world-mutation
 * vector not explicitly handled here bypasses protection by construction.
 *
 * <p>{@link #onBreak} runs at {@link EventPriority#HIGHEST} with
 * {@code ignoreCancelled = true}: without this, a protection plugin
 * (WorldGuard, GriefPrevention...) cancelling the break at a later
 * priority than a naive {@code NORMAL} handler would still have already
 * let items drop or the chest get flagged unbreakable-bypassed before the
 * cancellation was visible — running last and ignoring already-cancelled
 * events means this only ever acts once the break is genuinely going to
 * happen.
 *
 * <p>Once emptied, a death chest despawns immediately (see {@link #onClose})
 * rather than lingering as a real, minable {@code Material.CHEST} block.
 * Any current viewer is force-closed before contents are dropped by
 * {@link #dropRemainingAndClear} (expiry, or a bypass-permission removal)
 * — without this, a player mid-transfer (an item on their cursor from
 * that exact inventory) when this fires could end up with that item both
 * dropped on the ground AND still on their cursor, a partial duplication.
 * {@link #onClose} itself re-checks the live block type before removing
 * it, since the chest may have already been removed by another code path
 * between the close event being queued and handled.
 *
 * <p>Hoppers are blocked from draining or filling a death chest too (see
 * {@link #onItemMove}), since they bypass {@link #onOpen}'s protection
 * entirely by never firing an inventory-open event.
 */
public class DeathChestManager implements Listener {

    private static final int SEARCH_RADIUS = 5;

    // Fixed facing for every death chest. With this facing, a double chest
    // only visually/functionally merges when the second half sits directly
    // WEST (making the first chest LEFT) or EAST (making it RIGHT) of the
    // first — this is a vanilla rule tied to the chosen facing direction,
    // not an arbitrary choice, so the search for a second half is
    // restricted to exactly those two directions.
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
    private final NamespacedKey xpBottleOwnerKey;

    public DeathChestManager(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
        this.ownerKey = new NamespacedKey(plugin, "death_chest_owner");
        this.createdAtKey = new NamespacedKey(plugin, "death_chest_created");
        this.xpAmountKey = new NamespacedKey(plugin, "death_chest_xp_amount");
        this.xpBottleOwnerKey = new NamespacedKey(plugin, "death_chest_xp_bottle_owner");

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

            Location primary = findPlacementSpot(player.getLocation(), player);
            if (primary == null) {
                return;
            }

            Location secondary = plannedDrops.size() > 27 ? findAdjacentSpot(primary, player) : null;

            event.getDrops().clear();
            if (refundXp > 0) {
                event.setDroppedExp(0);
            }

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
            for (ItemStack item : plannedDrops) {
                overflow.addAll(inventory.addItem(item).values());
            }

            for (ItemStack item : overflow) {
                player.getWorld().dropItemNaturally(primary, item);
            }

            logger.log(PoppyLogger.Category.DEATH_CHEST, player, "death chest created at "
                    + primary.getWorld().getName() + ": " + primary.getBlockX() + ", " + primary.getBlockY() + ", " + primary.getBlockZ()
                    + " (" + plannedDrops.size() + " items" + (secondary != null ? ", double chest" : "") + ")");

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

            if (chest.getBlock().getType() != Material.CHEST) {
                return;
            }

            if (isEmpty(event.getInventory())) {
                removeChestBlocks(holder);
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
    public void onEntityExplode(@NonNull EntityExplodeEvent event) {
        if (!config.deathChestEnabled()) {
            return;
        }
        try {
            event.blockList().removeIf(this::isDeathChestBlock);
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
            event.blockList().removeIf(this::isDeathChestBlock);
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
            if (event.getBlocks().stream().anyMatch(this::isDeathChestBlock)) {
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
            if (event.getBlocks().stream().anyMatch(this::isDeathChestBlock)) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onPistonRetract", e);
        }
    }

    private boolean isDeathChestBlock(Block block) {
        return block.getType() == Material.CHEST
                && block.getState() instanceof Chest chestState
                && chestState.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
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

    /**
     * Consumes a death-chest XP bottle on right-click, restoring the exact
     * XP it was created with — but only for the player it was bound to at
     * creation time (see {@link #xpBottleOwnerKey}); anyone else trying to
     * use one gets denied and keeps the bottle. Triggers on both
     * {@link Action#RIGHT_CLICK_AIR} and {@link Action#RIGHT_CLICK_BLOCK}
     * — a regular experience bottle throws on either, so this must
     * intercept both to reliably prevent the vanilla throw behavior.
     */
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
                return; // a regular experience bottle, not one of ours
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

            // Player#giveExp only takes an int — clamp the stored long at the point of
            // actually giving it back, not anywhere it's computed or stored, so the
            // stored value itself is never silently truncated.
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

    /**
     * Computed as {@code long} throughout, not {@code int} — the quadratic
     * per-level XP cost grows fast enough that a very high-level player
     * could otherwise silently overflow {@link Integer#MAX_VALUE} both
     * here and in the stored bottle amount.
     */
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

        new ArrayList<>(chestState.getInventory().getViewers()).forEach(HumanEntity::closeInventory);

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

    private Location findAdjacentSpot(Location primary, Player owner) {
        for (BlockFace face : EXTEND_FACES) {
            Location candidate = primary.clone().add(face.getDirection());
            if (isPlaceable(candidate, owner)) {
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