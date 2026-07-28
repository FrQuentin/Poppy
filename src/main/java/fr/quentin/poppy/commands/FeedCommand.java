package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.CooldownRegistry;
import fr.quentin.poppy.util.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /feed: fully restores the sender's food level and saturation,
 * rate-limited via {@code feed-cooldown-seconds} in config.yml, tracked
 * in a shared {@link CooldownStore} registered with {@link CooldownRegistry}
 * so it shows up in {@code /cooldowns}.
 */
public class FeedCommand extends SafeCommand {

    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public FeedCommand(JavaPlugin plugin, Messages messages, PoppyConfig config, CooldownRegistry registry) {
        super(plugin, messages);
        this.config = config;
        this.cooldown = new CooldownStore(plugin);
        registry.register("Feed", cooldown);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (player.getFoodLevel() >= 20 && player.getSaturation() >= 20.0f) {
            player.sendMessage(messages.get("feed.already-full"));
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("feed.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        cooldown.start(player.getUniqueId(), config.feedCooldownMillis());

        player.sendMessage(messages.get("feed.success"));
        return true;
    }
}