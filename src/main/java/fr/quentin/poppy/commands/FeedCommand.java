package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /feed: fully restores the sender's food level and saturation,
 * rate-limited via {@code feed-cooldown-seconds} in config.yml.
 *
 * <p>Unlike most other cooldown maps in this plugin (e.g.
 * {@code RtpCommand.lastUse}), {@code lastUse} here is deliberately
 * <b>not</b> cleared on quit — same reasoning as
 * {@code PoppyLoreListener}: clearing it would let a player reset their
 * own cooldown for free by disconnecting and reconnecting, effectively
 * making /feed spammable with no real limit. The cooldown is meant to
 * survive a disconnect/reconnect, and only resets on a full server
 * restart. This is safe memory-wise since each entry is just a UUID and
 * a long.
 */
public class FeedCommand extends SafeCommand {

    private final PoppyConfig config;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public FeedCommand(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        super(plugin, messages);
        this.config = config;
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

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("feed.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        player.sendMessage(messages.get("feed.success"));
        return true;
    }

    private long cooldownRemaining(UUID uuid) {
        long cooldownMillis = config.feedCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }
}