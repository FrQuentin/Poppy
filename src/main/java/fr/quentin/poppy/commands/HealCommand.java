package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /heal: fully restores the sender's health, rate-limited via
 * {@code heal-cooldown-seconds} in config.yml. Same shape as
 * {@link FeedCommand}, including the same reasoning for not clearing
 * {@code lastUse} on quit — that would let a player reset their own
 * cooldown for free by disconnecting and reconnecting.
 */
public class HealCommand extends SafeCommand {

    private final PoppyConfig config;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public HealCommand(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        super(plugin, messages);
        this.config = config;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        double maxHealth = maxHealth(player);
        if (player.getHealth() >= maxHealth) {
            player.sendMessage(messages.get("heal.already-full"));
            return true;
        }

        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("heal.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        player.setHealth(maxHealth);
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        player.sendMessage(messages.get("heal.success"));
        return true;
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private long cooldownRemaining(UUID uuid) {
        long cooldownMillis = config.healCooldownMillis();
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