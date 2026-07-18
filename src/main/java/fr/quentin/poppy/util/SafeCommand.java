package fr.quentin.poppy.util;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

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

    protected abstract boolean execute(CommandSender sender, Command command, String label, String[] args) throws Exception;
}