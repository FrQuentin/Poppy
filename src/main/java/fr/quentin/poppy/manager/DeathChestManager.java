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
 * even though the player never actually lost any XP — letting them redeem
 * the bottle on top of their untouched vanilla XP, an infinite levels farm.
 *
 * <p>Ownership and creation time are stored directly on the chest block's
 * {@link org.bukkit.persistence.PersistentDataContainer} (chests are tile
 * entities, so this survives a server restart without any extra file).
 * The scheduled expiry task from {@link #onDeath} does <b>not</b> survive a
 * restart, though — a chest created right before a shutdown would
 * otherwise stay protected and unbreakable forever, with the
 * {@code createdAt} timestamp sitting unread in its PDC. The constructor
 * and {@link #onChunkLoad} cover that gap: every already-loaded chunk is
 * scanned at startup, and every chunk is scanned again as it loads, so an
 * expired chest is caught (and its remaining contents dropped) even if the
 * server was off past its expiry time.
 *
 * <p>XP refund is a percentage of the player's total XP, not a fixed
 * amount — {@code death-chest-xp-refund-percent} in config.yml, 100 by
 * default. At 100, the death XP penalty is fully negated (the bottle
 * gives back everything). Admins who want death to still cost XP should
 * lower this; vanilla itself only drops {@code min(7 × level, 100)} on
 * death, so this plugin's default is meaningfully more forgiving than
 * vanilla unless explicitly tuned down.
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
 * the block without ever firing {@link BlockBreakEvent}, so relying only
 * on {@link #onBreak}/{@link #onOpen}/{@link #onItemMove} left those two
 * vectors completely open: anyone could blow up or push someone else's
 * death chest to loot (or, combined with bad timing around emptying, dupe)
 * its contents. This event-by-event protection model is inherently
 * fragile — any future world-mutation vector not explicitly handled here
 * bypasses protection by construction. Fully closing that gap would mean
 * not storing contents in a real world block at all (an in-memory/file
 * inventory behind a cosmetic chest), which is a larger redesign than
 * this incremental fix.
 *
 * <p>Once emptied, a death chest despawns immediately (see {@link #onClose})
 * rather than lingering as a real, minable {@code Material.CHEST} block, and
 * breaking it is blocked outright (see {@link #onBreak}) — without both of
 * these, a player could deliberately die repeatedly and either break or
 * empty-then-mine each chest for a free chest item, an infinite farm.
 * Hoppers are blocked from draining or filling a death chest too (see
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

        if (event.getKeepInventory() || event.getKeepLevel()) {
            // Both gamerules mean the player already keeps everything vanilla-side —
            // nothing for this plugin to compensate for. Bailing out here (rather than
            // just relying on event.getDrops() being empty) matters specifically for
            // keepLevel: it doesn't affect getDrops() at all, so without this check
            // refundXp would still get computed and non-zero, and a chest holding just
            // an XP bottle would still get created even though items didn't need
            // replacing — letting the player keep their vanilla XP *and* redeem the
            // bottle for another full refund, an infinite levels farm.
            return;
        }

        try {
            Player player = event.getEntity();

            int refundXp = 0;
            int levelAtDeath = player.getLevel();
            if (config.deathChestStoreXp()) {
                int totalXp = getTotalExperience(player, levelAtDeath);
                refundXp = (int) ((long) totalXp * config.deathChestXpRefundPercent() / 100);
            }

            List<ItemStack> plannedDrops = new ArrayList<>(event.getDrops());
            if (refundXp > 0) {
                plannedDrops.add(createXpBottle(levelAtDeath, refundXp));
            }

            if (plannedDrops.isEmpty()) {
                return;
            }

            Location primary = findPlacementSpot(player.getLocation(), player);
            if (primary == null) {
                // No safe/unprotected spot found nearby: leave everything on the
                // vanilla path untouched — items drop as usual, and if refundXp was
                // going to replace the XP orbs, we never got the chance to commit
                // to that, so the orbs still drop normally too. Nothing is lost
                // beyond ordinary vanilla death behavior.
                return;
            }

            Location secondary = plannedDrops.size() > 27 ? findAdjacentSpot(primary, player) : null;

            // Only now, with a placement spot confirmed, do we commit to replacing
            // the vanilla drops with the chest + bottle.
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

    /**
     * Despawns a death chest the moment it's closed empty, so it can't be
     * mined afterward for a free chest item — see the class-level doc for
     * why that matters.
     */
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

            // The chest may have already been removed by another code path (expiry,
            // a bypass-permission break, etc.) between this close event being queued
            // and handled here — re-check the live block rather than trusting the
            // Chest snapshot, so we never attempt a redundant/racy removal.
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

    /**
     * Cancels breaking a death chest entirely — even for its owner, unless
     * they have the bypass permission. Without this, breaking the block
     * drops its contents on the ground regardless of {@link #onOpen}'s
     * protection (bypassing it for a non-owner stealing the items), and
     * also hands out a free chest item that could be farmed by repeatedly
     * dying and breaking each emptied chest.
     */
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

    /**
     * Strips death chest blocks out of any explosion's block list — a
     * creeper, a bed exploding in the Nether, TNT, or an end crystal can all
     * destroy a chest without ever firing {@link BlockBreakEvent}, letting
     * anyone loot (or, combined with a badly-timed empty/despawn, duplicate)
     * its contents. This is checked and fixed on the raw block list rather
     * than relying on {@link #onBreak}, {@link #onOpen}, or
     * {@link #onItemMove} alone — see the class-level doc for why enumerating
     * removal vectors one event at a time is inherently fragile.
     */
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

    /**
     * Same protection as {@link #onEntityExplode}, for block-sourced
     * explosions.
     */
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

    /**
     * Pushing a death chest with a piston moves the block (and, for a
     * double chest, can separate its two halves) without ever firing
     * {@link BlockBreakEvent} — cancelled outright rather than allowed to
     * relocate, which would also risk stripping its
     * {@link org.bukkit.persistence.PersistentDataContainer} tags in some
     * edge cases and effectively unprotect it.
     */
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

    /**
     * Same protection as {@link #onPistonExtend}, for sticky pistons
     * pulling a death chest back.
     */
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

    /**
     * Blocks hoppers (and hopper minecarts) from either draining or filling
     * a death chest. Hoppers never fire {@link InventoryOpenEvent} — they
     * transfer items via a completely different event — so without this,
     * {@link #onOpen}'s ownership check and the whole anti-farm design
     * could be bypassed simply by placing a hopper under (or above)
     * someone else's death chest.
     */
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
     * XP it was created with. Triggers on both {@link Action#RIGHT_CLICK_AIR}
     * and {@link Action#RIGHT_CLICK_BLOCK} — a regular experience bottle
     * throws on either, so this must intercept both to reliably prevent
     * the vanilla throw behavior.
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

            int storedXp = meta.getPersistentDataContainer().getOrDefault(xpAmountKey, PersistentDataType.INTEGER, 0);
            if (storedXp <= 0) {
                return; // a regular experience bottle, not one of ours
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

    /**
     * Scans every already-loaded chunk of every already-loaded world for
     * expired death chests, called once from the constructor. Covers
     * chests sitting in spawn-kept-loaded chunks (or any chunk a player
     * happened to already be standing in) at the moment the plugin enables
     * — {@link #onChunkLoad} covers everything else as chunks load later.
     */
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

    /**
     * Checks every tile entity in the chunk for a death chest whose
     * {@code createdAt} timestamp is past the configured expiry, and
     * expires it via {@link #dropRemainingAndClear} — the same cleanup
     * path used by the normal in-session {@link #expireChest}. Each half
     * of a double chest carries its own copy of the owner/createdAt tags
     * (see {@link #placeChest}), so this naturally handles both halves
     * independently without needing to know which location was "primary".
     */
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

    /**
     * A double chest's InventoryHolder is a {@link DoubleChest}, not a
     * {@link Chest} — this resolves either case to one underlying Chest so
     * ownership can be read the same way regardless of chest size.
     */
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

    /**
     * Verifies the block is still the exact chest we placed (it could have
     * already been emptied and despawned by {@link #onClose}, or broken)
     * before touching it — matching on the stored owner avoids destroying
     * an unrelated chest someone else placed at the same coordinates.
     *
     * <p>Any current viewer is force-closed before contents are touched —
     * without this, a player mid-transfer (holding an item on their cursor
     * from this exact inventory) when this fires could end up with that item
     * both dropped on the ground below AND still on their cursor, a partial
     * duplication. Viewers are read from {@link Chest#getInventory()} (the
     * merged double-chest inventory when applicable), not
     * {@link Chest#getBlockInventory()}, since that's the actual inventory a
     * player has open — closing only the raw single-block inventory wouldn't
     * necessarily close the GUI they're really looking at.
     */
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

        // Copy the viewer list first — closing an inventory mutates the list we'd
        // otherwise be iterating.
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

    /**
     * Finds a spot to place the primary chest: the death location itself if
     * placeable and not hazardous, otherwise the closest such spot within
     * {@link #SEARCH_RADIUS} blocks — same search shape as
     * {@link fr.quentin.poppy.commands.DeathBackCommand}'s safe-spot search,
     * but "placeable" here means the block is replaceable, not "safe to
     * stand on".
     */
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

    /**
     * Looks for a spot to place the second half of a double chest, only
     * along {@link #EXTEND_FACES} — the two directions that actually merge
     * with {@link #CHEST_FACING}. Any other direction would place a second,
     * unrelated single chest right next to the first instead of a proper
     * double chest.
     */
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

    /**
     * Fires a simulated {@link BlockPlaceEvent} for the given location
     * before any world mutation happens, so protection plugins
     * (WorldGuard, GriefPrevention, Lands...) that listen on that event
     * get a chance to cancel it — exactly as if the player had physically
     * placed a chest there. Without this, {@code block.setType(...)}
     * bypasses the whole protection plugin ecosystem entirely, since it
     * mutates the world at the engine level with no event involved.
     *
     * <p>The block is not modified before this check — {@code replacedState}
     * is a snapshot of the block's current (pre-placement) state, and
     * {@code placedBlock} is the same {@link Block} reference the world
     * still has as-is. Protection plugins check the location and the
     * player, not the physical material, so this works without a
     * temporary real placement and revert.
     */
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

    /**
     * Resolves an owner UUID string to a name for logging when no
     * {@link Player} object is at hand (e.g. during expiry, or when the
     * closer isn't the owner). Falls back to the raw UUID string if the
     * name can't be resolved.
     */
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