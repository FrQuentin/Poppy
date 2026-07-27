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
 * excluded via {@code silkspawner-blacklist} (e.g. to keep a
 * Wither Skeleton farm from being trivially relocated).
 *
 * <p>Only applies in Survival — Creative already never drops items on
 * block break regardless of enchantments (vanilla behavior), so allowing
 * this in Creative would hand out free spawners for nothing.
 *
 * <p>Breaking a spawner normally drops XP (15-43 points) regardless of
 * tool — vanilla never lets that be exploited since the block is gone for
 * good afterward. Once pickup-and-replace is possible, though, that XP
 * becomes an infinite farm (break, replace, break again...) unless it's
 * suppressed specifically on a successful Silk Touch pickup — see
 * {@code event.setExpToDrop(0)} in {@link #onBreak}. A blacklisted or
 * non-Silk-Touch break still destroys the block for good, exactly like
 * vanilla, so its XP is left untouched in that case.
 *
 * <p><b>Split decide/materialize to close a duplication window:</b>
 * {@link #onBreak} runs at {@link EventPriority#HIGHEST} — high, but not
 * last. Any listener registered at {@code HIGHEST} after this one (load
 * order between plugins isn't something Poppy controls), or at
 * {@link EventPriority#MONITOR}, can still cancel the break after this
 * class has already decided to hand out a spawner item. Dropping the item
 * immediately at {@code HIGHEST} — the old design — meant a later
 * cancellation left the block standing <i>and</i> the item already on the
 * ground: an infinite spawner duplication loop (break, get item, break
 * cancelled by a claim plugin, block still there, repeat). Instead,
 * {@link #onBreak} only records the decision in {@link #pendingDrops};
 * {@link #onBreakMonitor}, at {@code MONITOR} (guaranteed to run after
 * every other plugin's handler), is what actually drops the item — and
 * only after re-checking {@link BlockBreakEvent#isCancelled()} and, a
 * tick later, that the block has genuinely become air. If anything
 * cancelled the break in between, nothing is dropped and the spawner
 * survives untouched, so there's never a state where both the block and
 * the item exist.
 *
 * <p>The spawner's mob type is read from the broken block's
 * {@link CreatureSpawner} state and stored on the dropped item via
 * {@link org.bukkit.persistence.PersistentDataContainer}, then read back
 * and re-applied to the newly placed block in {@link #onPlace} — so the
 * item always places as the same mob type it was picked up as, with a
 * name/lore reflecting that mob (e.g. "Skeleton Spawner" / "Skeleton").
 */
public class SilkSpawnerListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final NamespacedKey entityTypeKey;

    /**
     * Chest-break decisions awaiting confirmation at {@link #onBreakMonitor}.
     * Keyed by block location since that's stable across the two handler
     * calls for the same physical break; removed as soon as it's consumed
     * (or discarded on cancellation) so it never lingers.
     */
    private final Map<Location, EntityType> pendingDrops = new HashMap<>();

    public SilkSpawnerListener(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.entityTypeKey = new NamespacedKey(plugin, "silk_spawner_entity_type");
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

            event.setDropItems(false);
            event.setExpToDrop(0);
            pendingDrops.put(block.getLocation(), entityType);

            player.sendMessage(messages.get("silkspawner.success", "mob", formatName(entityType)));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in SilkSpawnerListener#onBreak for " + event.getPlayer().getName(), e);
        }
    }

    /**
     * Runs after every other plugin's handler has had a chance to cancel
     * the break — see the class-level doc. Only materializes the spawner
     * item once both this event is still uncancelled here, and (a tick
     * later, since some plugins revert a break asynchronously-adjacent to
     * this point rather than via cancellation) the block has actually
     * become air.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakMonitor(@NonNull BlockBreakEvent event) {
        try {
            Block block = event.getBlock();
            EntityType entityType = pendingDrops.remove(block.getLocation());
            if (entityType == null) {
                return;
            }

            if (event.isCancelled()) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (block.getType() != Material.SPAWNER) {
                    block.getWorld().dropItemNaturally(block.getLocation(), buildSpawnerItem(entityType));
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

            if (!(event.getBlock().getState() instanceof CreatureSpawner spawnerState)) {
                return;
            }

            spawnerState.setSpawnedType(entityType);
            spawnerState.update(true, false);
        } catch (IllegalArgumentException e) {
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

        item.setItemMeta(meta);
        return item;
    }

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