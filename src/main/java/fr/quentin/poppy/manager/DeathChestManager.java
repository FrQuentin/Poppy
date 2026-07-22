package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * in config.yml; if the {@code keepInventory} gamerule is on,
 * {@link PlayerDeathEvent#getDrops()} is already empty by the time this
 * runs, so nothing happens automatically — no special-casing needed.
 *
 * <p>Ownership and creation time are stored directly on the chest block's
 * {@link org.bukkit.persistence.PersistentDataContainer} (chests are tile
 * entities, so this survives a server restart without any extra file),
 * rather than in an in-memory map — the only thing that doesn't survive a
 * restart is the scheduled expiry task itself (see {@link #onDeath}), so a
 * chest created right before a restart keeps its protection indefinitely
 * until manually broken. Acceptable for a personal server; would need a
 * persisted chest registry to fix properly for a public one.
 *
 * <p>Once emptied, a death chest despawns immediately (see {@link #onClose})
 * rather than lingering as a real, minable {@code Material.CHEST} block, and
 * breaking it is blocked outright (see {@link #onBreak}) — without both of
 * these, a player could deliberately die repeatedly and either break or
 * empty-then-mine each chest for a free chest item, an infinite farm.
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
    private final NamespacedKey ownerKey;
    private final NamespacedKey createdAtKey;
    private final NamespacedKey xpAmountKey;
    private final boolean enabled;
    private final boolean protectChest;
    private final boolean storeXp;
    private final long expiryMillis;

    public DeathChestManager(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.ownerKey = new NamespacedKey(plugin, "death_chest_owner");
        this.createdAtKey = new NamespacedKey(plugin, "death_chest_created");
        this.xpAmountKey = new NamespacedKey(plugin, "death_chest_xp_amount");
        this.enabled = plugin.getConfig().getBoolean("death-chest-enabled", true);
        this.protectChest = plugin.getConfig().getBoolean("death-chest-protect", true);
        this.storeXp = plugin.getConfig().getBoolean("death-chest-store-xp", true);
        long expiryMinutes = Math.max(0, plugin.getConfig().getInt("death-chest-expiry-minutes", 30));
        this.expiryMillis = expiryMinutes * 60L * 1000L;
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }

        try {
            Player player = event.getEntity();
            List<ItemStack> drops = new ArrayList<>(event.getDrops());

            if (storeXp) {
                int levelAtDeath = player.getLevel();
                int totalXp = getTotalExperience(player, levelAtDeath);
                if (totalXp > 0) {
                    drops.add(createXpBottle(levelAtDeath, totalXp));
                    event.setDroppedExp(0); // fully replaced by the bottle, no orbs on the ground too
                }
            }

            if (drops.isEmpty()) {
                return;
            }

            Location primary = findPlacementSpot(player.getLocation());
            if (primary == null) {
                // No safe spot found nearby: fall back to items dropping on the ground as usual.
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

            // Anything that didn't fit (single chest with >27 items and no room for a
            // second half nearby) drops naturally so nothing is silently deleted.
            for (ItemStack item : overflow) {
                player.getWorld().dropItemNaturally(primary, item);
            }

            player.sendMessage(messages.get("death.chest-created",
                    "x", String.valueOf(primary.getBlockX()),
                    "y", String.valueOf(primary.getBlockY()),
                    "z", String.valueOf(primary.getBlockZ())));

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
        if (!enabled || !protectChest) {
            return;
        }

        try {
            Chest chest = resolveChest(event.getInventory().getHolder());
            if (chest == null) {
                return;
            }

            String ownerString = chest.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerString == null) {
                return; // not a death chest
            }

            if (!(event.getPlayer() instanceof Player opener)) {
                return;
            }

            boolean isOwner = ownerString.equals(opener.getUniqueId().toString());
            boolean bypass = opener.hasPermission("poppy.deathchest.bypass");

            if (!isOwner && !bypass) {
                event.setCancelled(true);
                opener.sendMessage(messages.get("death.chest-not-yours"));
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
        if (!enabled) {
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
                return; // not a death chest
            }

            if (isEmpty(event.getInventory())) {
                removeChestBlocks(holder);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onClose", e);
        }
    }

    /**
     * Cancels breaking a death chest entirely — even for its owner. Without
     * this, breaking the block drops its contents on the ground regardless of
     * {@link #onOpen}'s protection (bypassing it for a non-owner stealing the
     * items), and also hands out a free chest item that could be farmed by
     * repeatedly dying and breaking each emptied chest.
     */
    @EventHandler
    public void onBreak(@NonNull BlockBreakEvent event) {
        if (!enabled) {
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
                return; // not a death chest
            }

            Player breaker = event.getPlayer();
            if (breaker.hasPermission("poppy.deathchest.bypass")) {
                return;
            }

            event.setCancelled(true);
            breaker.sendMessage(messages.get("death.chest-unbreakable"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathChestManager#onBreak", e);
        }
    }

    @EventHandler
    public void onXpBottleUse(@NonNull PlayerInteractEvent event) {
        if (!enabled || !storeXp) {
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

            event.setCancelled(true); // prevents the vanilla throw behavior, even when a block was clicked

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

    private ItemStack createXpBottle(int levelAtDeath, int totalXp) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = bottle.getItemMeta();

        meta.displayName(messages.get("death.xp-bottle-name", "levels", String.valueOf(levelAtDeath)));
        meta.lore(List.of(messages.get("death.xp-bottle-lore", "levels", String.valueOf(levelAtDeath))));
        meta.getPersistentDataContainer().set(xpAmountKey, PersistentDataType.INTEGER, totalXp);

        bottle.setItemMeta(meta);
        return bottle;
    }

    /**
     * Converts the player's current level + progress into a raw XP point
     * total, using the vanilla level-cost formulas directly rather than
     * {@link Player#getTotalExperience()} — that field is well known to
     * drift out of sync with the displayed level/progress after certain
     * operations, so it isn't reliable enough for an exact refund later.
     */
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
            dropRemainingAndClear(primary, owner);
            if (secondary != null) {
                dropRemainingAndClear(secondary, owner);
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
     */
    private void dropRemainingAndClear(Location location, UUID owner) {
        Block block = location.getBlock();
        if (block.getType() != Material.CHEST) {
            return;
        }

        Chest chestState = (Chest) block.getState();
        String ownerString = chestState.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerString == null || !ownerString.equals(owner.toString())) {
            return;
        }

        for (ItemStack item : chestState.getBlockInventory().getContents()) {
            if (item != null) {
                location.getWorld().dropItemNaturally(location, item);
            }
        }

        block.setType(Material.AIR);
    }

    /**
     * Finds a spot to place the primary chest: the death location itself if
     * placeable and not hazardous, otherwise the closest such spot within
     * {@link #SEARCH_RADIUS} blocks — same search shape as
     * {@link fr.quentin.poppy.commands.DeathBackCommand}'s safe-spot search,
     * but "placeable" here means the block is replaceable, not "safe to
     * stand on".
     */
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

    /**
     * Looks for a spot to place the second half of a double chest, only
     * along {@link #EXTEND_FACES} — the two directions that actually merge
     * with {@link #CHEST_FACING}. Any other direction would place a second,
     * unrelated single chest right next to the first instead of a proper
     * double chest.
     */
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
        return BlockFace.SOUTH; // unused fallback: EXTEND_FACES only ever produces an X-axis offset
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
}