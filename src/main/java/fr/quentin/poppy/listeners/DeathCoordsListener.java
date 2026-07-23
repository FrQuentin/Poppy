package fr.quentin.poppy.listeners;

import fr.quentin.poppy.manager.DeathLocationManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

/**
 * Sends the player their death coordinates in chat with a clickable
 * teleport link, toggleable via {@code death-coords-enabled} in
 * config.yml. The location is recorded in {@link DeathLocationManager} and
 * resolved server-side by {@code /deathback} — see
 * {@link fr.quentin.poppy.commands.DeathBackCommand} — rather than encoding
 * coordinates in the clickable command itself.
 *
 * <p>Kept separate from {@link fr.quentin.poppy.manager.BackListener}
 * (which records the same death location for /back) since this is a
 * notification concern, not a teleport one — a natural place to later add
 * "store the player's items in a chest at the death location" without
 * touching /back's logic.
 */
public class DeathCoordsListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final DeathLocationManager deathLocationManager;
    private final PoppyLogger logger;
    private final boolean enabled;

    public DeathCoordsListener(JavaPlugin plugin, Messages messages, DeathLocationManager deathLocationManager, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.deathLocationManager = deathLocationManager;
        this.logger = logger;
        this.enabled = plugin.getConfig().getBoolean("death-coords-enabled", true);
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }

        try {
            Player player = event.getEntity();
            Location location = player.getLocation();

            deathLocationManager.recordDeath(player.getUniqueId(), location);

            String worldLabel = worldLabel(location.getWorld());
            logger.log(PoppyLogger.Category.DEATH, player, "died at " + worldLabel + ": "
                    + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());

            Component prefix = messages.get("death.coords",
                    "world", worldLabel,
                    "x", String.valueOf(location.getBlockX()),
                    "y", String.valueOf(location.getBlockY()),
                    "z", String.valueOf(location.getBlockZ()));
            Component click = messages.get("death.coords-click")
                    .clickEvent(ClickEvent.runCommand("/deathback"));

            player.sendMessage(prefix.append(click));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in DeathCoordsListener#onDeath for " + event.getEntity().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        deathLocationManager.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Same mapping as {@link fr.quentin.poppy.gui.HomesGUI#worldLabel} —
     * shows a friendly dimension name instead of the raw world folder name
     * (e.g. "world_nether"), which players never think of the Nether as.
     */
    private String worldLabel(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }
}