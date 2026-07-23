package fr.quentin.poppy.listeners;

import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Applies the configured {@code sleep-percentage} (config.yml) to the
 * vanilla {@link GameRules#PLAYERS_SLEEPING_PERCENTAGE} gamerule on every
 * world — both the ones already loaded when the plugin starts, and any
 * loaded afterward (e.g. a new dimension generated on first use). This
 * relies entirely on vanilla's own sleep-skip logic rather than tracking
 * sleeping players manually: it already handles counting sleepers, the
 * threshold, waking everyone, advancing time, and clearing weather.
 *
 * <p>Uses the new {@code org.bukkit.GameRules} registry-backed class
 * (introduced alongside Mojang's snake_case gamerule rewrite around
 * 1.21.11) rather than the older {@code org.bukkit.GameRule} static
 * constants, which are deprecated for removal as of that version.
 */
public class SleepPercentageListener implements Listener {

    private final int percentage;

    public SleepPercentageListener(JavaPlugin plugin) {
        this.percentage = Math.clamp(plugin.getConfig().getInt("sleep-percentage", 50), 0, 100);
        for (World world : plugin.getServer().getWorlds()) {
            apply(world);
        }
    }

    @EventHandler
    public void onWorldLoad(@NonNull WorldLoadEvent event) {
        apply(event.getWorld());
    }

    private void apply(World world) {
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, percentage);
    }
}