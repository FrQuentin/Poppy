package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * A gamerule isn't read live by the game — it must be re-pushed to every
 * world whenever the value changes, which is why {@link #reapply()}
 * exists and is called explicitly by {@code /poppy reload}.
 */
public class SleepPercentageListener implements Listener {

    private final JavaPlugin plugin;
    private final PoppyConfig config;

    public SleepPercentageListener(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        reapply();
    }

    @EventHandler
    public void onWorldLoad(@NonNull WorldLoadEvent event) {
        apply(event.getWorld());
    }

    public void reapply() {
        for (World world : plugin.getServer().getWorlds()) {
            apply(world);
        }
    }

    private void apply(World world) {
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, config.sleepPercentage());
    }
}