package fr.quentin.poppy.commands.misc;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /bugs: shows a clickable link to the plugin's GitHub issues
 * page. Works for both players and console.
 */
public class BugsCommand extends SafeCommand {

    public BugsCommand(JavaPlugin plugin, Messages messages) {
        super(plugin, messages);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        sender.sendMessage(messages.get("misc.bugs"));
        return true;
    }
}