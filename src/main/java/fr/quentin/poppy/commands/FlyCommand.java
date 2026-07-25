package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.FlyManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /fly: toggles flight for the sender, blocked for a short
 * lockout right after taking damage — see {@link FlyManager}.
 */
public class FlyCommand extends SafeCommand {

    private final FlyManager flyManager;

    public FlyCommand(JavaPlugin plugin, FlyManager flyManager, Messages messages) {
        super(plugin, messages);
        this.flyManager = flyManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (flyManager.isLocked(player.getUniqueId())) {
            long remaining = flyManager.displayRemainingSeconds(player.getUniqueId());
            player.sendMessage(messages.get("fly.locked-out", "time", DurationFormat.format(remaining)));
            return true;
        }

        FlyManager.ToggleResult result = flyManager.toggle(player);
        switch (result) {
            case ENABLED -> player.sendMessage(messages.get("fly.enabled"));
            case DISABLED -> player.sendMessage(messages.get("fly.disabled"));
            case BLOCKED_END -> player.sendMessage(messages.get("fly.blocked-end"));
        }
        return true;
    }
}