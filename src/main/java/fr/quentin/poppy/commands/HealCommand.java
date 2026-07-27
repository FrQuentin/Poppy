package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.CooldownStore;
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

/**
 * Handles /heal: fully restores the sender's health, rate-limited via
 * {@code heal-cooldown-seconds} in config.yml, tracked in a shared
 * {@link CooldownStore} (see its class-level doc).
 */
public class HealCommand extends SafeCommand {

    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public HealCommand(JavaPlugin plugin, Messages messages, PoppyConfig config) {
        super(plugin, messages);
        this.config = config;
        this.cooldown = new CooldownStore(plugin);
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

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("heal.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        player.setHealth(maxHealth);
        cooldown.start(player.getUniqueId(), config.healCooldownMillis());

        player.sendMessage(messages.get("heal.success"));
        return true;
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }
}