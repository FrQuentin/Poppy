package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Lets players pick up mob spawners with a Silk Touch tool, matching a
 * common feature many servers expect but vanilla doesn't provide (vanilla
 * spawners never drop, regardless of the tool). Toggleable via
 * {@code silkspawner-enabled} in config.yml; specific entity types can be
 * excluded via {@code silkspawner-blacklist}.
 *
 * <p>Only applies in Survival — Creative already never drops items on
 * block break regardless of enchantments.
 *
 * <p>Breaking a spawner normally drops XP regardless of tool; on a
 * successful Silk Touch pickup that XP is suppressed (see
 * {@code event.setExpToDrop(0)} in {@link #onBreak}), otherwise
 * pick-up-and-replace would be an infinite XP farm.
 *
 * <p><b>Split decide/materialize to close a duplication window:</b>
 * {@link #onBreak} runs at {@link EventPriority#HIGHEST} — high, but not
 * last. Any listener registered at {@code HIGHEST} after this one, or at
 * {@link EventPriority#MONITOR}, can still cancel the break after this
 * class has already decided to hand out a spawner item. Instead,
 * {@link #onBreak} only records the decision in {@link #pendingDrops};
 * {@link #onBreakMonitor}, at {@code MONITOR} (guaranteed to run after
 * every other plugin's handler), is what actually sends the success
 * message and drops the item — and only after re-checking
 * {@link BlockBreakEvent#isCancelled()} and, a tick later, that the block
 * has genuinely become air. The success message itself is sent here too,
 * not in {@link #onBreak} — sending it earlier risked telling the player
 * they got a spawner item right before a protection plugin's later
 * cancellation left them with nothing.
 *
 * <p>{@link #notifiedKey} tracks, per physical spawner (not per item
 * instance, which is rebuilt on every break), whether the "you picked up
 * a spawner" chat message has already been shown for it once. Written
 * into the dropped item's PDC in {@link #buildSpawnerItem} and copied
 * back onto the block's PDC in {@link #onPlace} — so it survives an
 * arbitrary number of break/place cycles for the same spawner. Without
 * this, a player relocating a spawner (break, place, break again) would
 * get the success message spammed in chat every single time.
 *
 * <p>The spawner's mob type is read from the broken block's
 * {@link CreatureSpawner} state and stored on the dropped item, then read
 * back and re-applied to the newly placed block in {@link #onPlace} —
 * re-checking the blacklist, the {@code poppy.silkspawner} permission,
 * and a sanity check ({@code isSpawnable()}/{@code isAlive()}) at that
 * point too, not just at break time: an item obtained before a type was
 * blacklisted, handed to a player without the permission, or forged with
 * an arbitrary PDC value (a hand-crafted NBT string via a Creative
 * {@code /give}) would otherwise stay placeable/uncontrolled forever.
 */
public class SilkSpawnerListener implements Listener {

    private record PendingDrop(EntityType entityType, boolean alreadyNotified) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final NamespacedKey entityTypeKey;
    private final NamespacedKey notifiedKey;

    /**
     * Break decisions awaiting confirmation at {@link #onBreakMonitor}.
     * Keyed by block location since that's stable across the two handler
     * calls for the same physical break; removed as soon as it's consumed
     * (or discarded on cancellation) so it never lingers.
     */
    private final Map<Location, PendingDrop> pendingDrops = new HashMap<>();

    public SilkSpawnerListener(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.entityTypeKey = new NamespacedKey(plugin, "silk_spawner_entity_type");
        this.notifiedKey = new NamespacedKey(plugin, "silk_spawner_notified");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(@NonNull BlockBreakEvent event) {
        if (!config.silkSpawnerEnabled()) {
            return;
        }

        try {
            Block block = event.getBlock();
            if (block.getType() != Material.SPAWNER) {
                return;
            }

            Player player = event.getPlayer();
            if (player.getGameMode() == GameMode.CREATIVE) {
                return;
            }

            if (!player.hasPermission("poppy.silkspawner")) {
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                return;
            }

            if (!(block.getState() instanceof CreatureSpawner spawnerState)) {
                return;
            }

            EntityType entityType = spawnerState.getSpawnedType();
            if (entityType == null) {
                entityType = EntityType.PIG; // vanilla default for a never-configured spawner
            }

            if (config.silkSpawnerBlacklist().contains(entityType.name())) {
                player.sendMessage(messages.get("silkspawner.blacklisted", "mob", formatName(entityType)));
                return;
            }

            boolean alreadyNotified = spawnerState.getPersistentDataContainer()
                    .getOrDefault(notifiedKey, PersistentDataType.INTEGER, 0) == 1;

            event.setDropItems(false);
            event.setExpToDrop(0);
            pendingDrops.put(block.getLocation(), new PendingDrop(entityType, alreadyNotified));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in SilkSpawnerListener#onBreak for " + event.getPlayer().getName(), e);
        }
    }

    /**
     * Runs after every other plugin's handler has had a chance to cancel
     * the break — see the class-level doc. Only sends the success message
     * and materializes the spawner item once both this event is still
     * uncancelled here, and (a tick later) the block has actually become
     * air.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakMonitor(@NonNull BlockBreakEvent event) {
        try {
            if (pendingDrops.isEmpty()) {
                return;
            }

            Block block = event.getBlock();
            PendingDrop pending = pendingDrops.remove(block.getLocation());
            if (pending == null) {
                return;
            }

            if (event.isCancelled()) {
                return;
            }

            Player player = event.getPlayer();

            if (!pending.alreadyNotified()) {
                player.sendMessage(messages.get("silkspawner.success", "mob", formatName(pending.entityType())));
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (block.getType() != Material.SPAWNER) {
                    block.getWorld().dropItemNaturally(block.getLocation(), buildSpawnerItem(pending.entityType()));
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in SilkSpawnerListener#onBreakMonitor for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onPlace(@NonNull BlockPlaceEvent event) {
        if (!config.silkSpawnerEnabled()) {
            return;
        }

        try {
            if (event.getBlock().getType() != Material.SPAWNER) {
                return;
            }

            ItemMeta meta = event.getItemInHand().getItemMeta();
            if (meta == null) {
                return;
            }

            String storedType = meta.getPersistentDataContainer().get(entityTypeKey, PersistentDataType.STRING);
            if (storedType == null) {
                return; // a plain, non-custom spawner item
            }

            EntityType entityType = EntityType.valueOf(storedType);
            Player player = event.getPlayer();

            // Re-checked here, not just at break time — see the class-level doc.
            if (config.silkSpawnerBlacklist().contains(entityType.name())) {
                event.setCancelled(true);
                player.sendMessage(messages.get("silkspawner.blacklisted", "mob", formatName(entityType)));
                return;
            }

            if (!player.hasPermission("poppy.silkspawner")) {
                event.setCancelled(true);
                player.sendMessage(messages.get("general.no-permission"));
                return;
            }

            if (!entityType.isSpawnable() || !entityType.isAlive()) {
                event.setCancelled(true);
                player.sendMessage(messages.get("silkspawner.blacklisted", "mob", formatName(entityType)));
                return;
            }

            if (!(event.getBlock().getState() instanceof CreatureSpawner spawnerState)) {
                return;
            }

            spawnerState.setSpawnedType(entityType);
            // Propagate the "already notified" flag onto the block too, so a future
            // break of this exact spawner also sees it — see the class-level doc.
            spawnerState.getPersistentDataContainer().set(notifiedKey, PersistentDataType.INTEGER, 1);
            spawnerState.update(true, false);
        } catch (IllegalArgumentException e) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.WARNING, "Silk-touched spawner item had an invalid stored entity type", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in SilkSpawnerListener#onPlace for " + event.getPlayer().getName(), e);
        }
    }

    private ItemStack buildSpawnerItem(EntityType entityType) {
        ItemStack item = new ItemStack(Material.SPAWNER, 1);
        ItemMeta meta = item.getItemMeta();

        String name = formatName(entityType);
        meta.displayName(Component.text(name + " Spawner", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(entityTypeKey, PersistentDataType.STRING, entityType.name());
        // Once an item exists, this spawner has necessarily already been broken (and
        // therefore already notified) once — carry that forward so a later re-place
        // keeps the flag on the block too.
        meta.getPersistentDataContainer().set(notifiedKey, PersistentDataType.INTEGER, 1);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Converts a vanilla entity type constant into a readable name, e.g.
     * {@code WITHER_SKELETON} → "Wither Skeleton" — works for any current
     * or future {@link EntityType} without a hand-maintained name list.
     */
    private String formatName(EntityType entityType) {
        String[] parts = entityType.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            builder.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}