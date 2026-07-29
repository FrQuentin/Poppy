package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.CooldownRegistry;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /cooldowns: lists every cooldown registered in
 * {@link CooldownRegistry} for the sender, showing either the remaining
 * time or "ready" for each.
 */
public class CooldownsCommand extends SafeCommand {

    private final CooldownRegistry registry;

    public CooldownsCommand(JavaPlugin plugin, Messages messages, CooldownRegistry registry) {
        super(plugin, messages);
        this.registry = registry;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (registry.entries().isEmpty()) {
            player.sendMessage(messages.get("cooldowns.none"));
            return true;
        }

        player.sendMessage(messages.get("cooldowns.header"));
        player.sendMessage(Component.empty());

        for (CooldownRegistry.Entry entry : registry.entries()) {
            long remaining = entry.remainingSecondsProvider().apply(player.getUniqueId());
            if (remaining > 0) {
                player.sendMessage(messages.get("cooldowns.line-active", "name", entry.displayName(), "time", DurationFormat.format(remaining)));
            } else {
                player.sendMessage(messages.get("cooldowns.line-ready", "name", entry.displayName()));
            }
        }

        return true;
    }
}