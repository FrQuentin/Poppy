package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Feeds player activity into {@link AfkManager} from a broad set of
 * signals, not just movement: mining, placing blocks, interacting,
 * clicking inventories, running commands, and chatting all count.
 *
 * <p>Movement alone used to be the only tracked signal. A player mining
 * in place, fishing, sorting a chest, building without stepping off their
 * block, fighting a mob that isn't pushing them around, or just chatting
 * for several minutes would still get auto-marked AFK and announced
 * server-wide — a constant stream of false positives for entirely normal
 * play. {@link #onActivity} is the shared handler every signal funnels
 * through: it records the timestamp and, if the player was AFK, clears
 * the status and broadcasts the "no longer AFK" message, exactly like
 * {@link #onMove} always did on its own.
 *
 * <p>{@link #onChat} is the one signal that fires off the main thread
 * ({@link AsyncChatEvent}). Rather than making {@link AfkManager} itself
 * thread-safe for the sake of one relatively infrequent event, the whole
 * activity update is deferred a tick via {@link Bukkit#getScheduler()} —
 * chat happens far less often than movement, so the extra scheduling has
 * no measurable cost, and {@link AfkManager} stays a plain, simple,
 * main-thread-only class.
 */
public class AfkListener implements Listener {

    private final JavaPlugin plugin;
    private final AfkManager afkManager;
    private final Messages messages;
    private final PoppyLogger logger;

    public AfkListener(JavaPlugin plugin, AfkManager afkManager, Messages messages, PoppyLogger logger) {
        this.plugin = plugin;
        this.afkManager = afkManager;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        afkManager.recordActivity(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        afkManager.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(@NonNull PlayerMoveEvent event) {
        try {
            Player player = event.getPlayer();

            Location from = event.getFrom();
            Location to = event.getTo();

            if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
                return;
            }

            onActivity(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onMove for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NonNull PlayerInteractEvent event) {
        try {
            onActivity(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onInteract for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NonNull BlockBreakEvent event) {
        try {
            onActivity(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onBreak for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NonNull BlockPlaceEvent event) {
        try {
            onActivity(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onPlace for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(@NonNull InventoryClickEvent event) {
        try {
            if (event.getWhoClicked() instanceof Player player) {
                onActivity(player);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onInventoryClick for " + event.getWhoClicked().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(@NonNull PlayerCommandPreprocessEvent event) {
        try {
            String root = event.getMessage().split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
            // /afk itself must stay record-only: clearing + broadcasting here
            // would fire right before AfkCommand re-toggles it, making /afk
            // unusable to leave AFK (the original bug this whole split was
            // written to fix). Any other command is a genuine activity signal
            // and should clear AFK just like a click or a movement — without
            // this, a player marked AFK who ran /home and kept playing purely
            // through commands stayed tagged [AFK] forever until they physically
            // moved or clicked something.
            if (root.equals("/afk")) {
                recordActivity(event.getPlayer());
            } else {
                onActivity(event.getPlayer());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onCommand for " + event.getPlayer().getName(), e);
        }
    }

    private void recordActivity(Player player) {
        afkManager.recordActivity(player.getUniqueId());
    }

    /**
     * Fires off the main thread — see the class-level doc for why the
     * whole activity update is deferred a tick rather than making
     * {@link AfkManager} thread-safe.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(@NonNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                onActivity(player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in AfkListener#onChat for " + player.getName(), e);
            }
        });
    }

    /**
     * Full activity handling: records the timestamp AND, if the player was
     * AFK, clears it and broadcasts the "no longer AFK" message. Used by
     * every activity signal except {@link #onCommand} — see its own doc for
     * why command preprocessing specifically must stay record-only.
     */
    private void onActivity(Player player) {
        recordActivity(player);

        if (!afkManager.isAfk(player.getUniqueId())) {
            return;
        }

        afkManager.clearAfk(player.getUniqueId());
        logger.log(PoppyLogger.Category.AFK, player, "returned from AFK (auto, activity)");

        Component broadcast = messages.get("afk.no-longer-afk", "player", player.getName());
        Bukkit.getServer().sendMessage(broadcast);
    }
}