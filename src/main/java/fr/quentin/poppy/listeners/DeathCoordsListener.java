package fr.quentin.poppy.listeners;

import fr.quentin.poppy.manager.DeathLocationManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
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

public class DeathCoordsListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final DeathLocationManager deathLocationManager;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    public DeathCoordsListener(JavaPlugin plugin, Messages messages, DeathLocationManager deathLocationManager,
                               PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.deathLocationManager = deathLocationManager;
        this.config = config;
        this.logger = logger;
    }

    @EventHandler
    public void onDeath(@NonNull PlayerDeathEvent event) {
        if (!config.deathCoordsEnabled()) {
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

    private String worldLabel(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }
}