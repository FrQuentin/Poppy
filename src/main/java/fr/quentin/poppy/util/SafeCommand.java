package fr.quentin.poppy.util;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.logging.Level;

/**
 * Base class for every Poppy command: wraps {@link #execute} in a
 * try/catch so an unexpected exception in any command never surfaces as
 * "An internal error occurred" or a stack trace to the sender — the person
 * just gets {@code general.error} and the real exception is logged with
 * full context (command, args, sender) for the console/admin to diagnose.
 *
 * <p>{@code onCommand} is {@code final} on purpose: every Poppy command goes
 * through this same safety net, so subclasses implement {@link #execute}
 * instead and never override the CommandExecutor entry point directly.
 */
public abstract class SafeCommand implements CommandExecutor {

    protected final JavaPlugin plugin;
    protected final Messages messages;

    protected SafeCommand(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public final boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        try {
            return execute(sender, command, label, args);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Unexpected error while executing /" + label + " (args: " + String.join(" ", args) + ", sender: " + sender.getName() + ")",
                    e);
            sender.sendMessage(messages.get("general.error"));
            return true;
        }
    }

    protected abstract boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) throws Exception;

    /**
     * Returns the sender as a Player, or sends the "only players" message and returns null
     * if it's console/a command block. Callers should return true right after a null check.
     */
    protected @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(messages.get("general.only-player"));
        return null;
    }
}