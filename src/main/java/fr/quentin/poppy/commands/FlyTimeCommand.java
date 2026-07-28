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
 * Handles /flytime: shows how much of the sender's cumulative /fly time
 * budget remains — see {@link FlyManager#remainingFlightBudgetSeconds}.
 */
public class FlyTimeCommand extends SafeCommand {

    private final FlyManager flyManager;

    public FlyTimeCommand(JavaPlugin plugin, FlyManager flyManager, Messages messages) {
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
            long lockoutRemaining = flyManager.displayRemainingSeconds(player.getUniqueId());
            player.sendMessage(messages.get("fly.time-locked", "time", DurationFormat.format(lockoutRemaining)));
            return true;
        }

        long remaining = flyManager.remainingFlightBudgetSeconds(player.getUniqueId());

        if (remaining < 0) {
            player.sendMessage(messages.get("fly.time-unlimited"));
            return true;
        }

        player.sendMessage(messages.get("fly.time-remaining", "time", DurationFormat.format(remaining)));
        return true;
    }
}