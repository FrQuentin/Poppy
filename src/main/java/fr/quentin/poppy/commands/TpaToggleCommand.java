package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.TpaManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /tpatoggle: toggles whether the sender accepts incoming /tpa
 * and /tpahere requests at all — see {@link TpaManager#toggleRequests}.
 */
public class TpaToggleCommand extends SafeCommand {

    private final TpaManager tpaManager;

    public TpaToggleCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        boolean nowAccepting = tpaManager.toggleRequests(player.getUniqueId());
        player.sendMessage(messages.get(nowAccepting ? "tpa.toggle-on" : "tpa.toggle-off"));
        return true;
    }
}