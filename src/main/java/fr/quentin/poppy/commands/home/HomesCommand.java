package fr.quentin.poppy.commands.home;

import fr.quentin.poppy.gui.home.HomesGUI;
import fr.quentin.poppy.manager.home.HomeManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /homes: opens the double-chest GUI listing all of the sender's
 * homes. Functionally equivalent to running /home with no argument.
 */
public class HomesCommand extends SafeCommand {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;

    public HomesCommand(JavaPlugin plugin, HomeManager homeManager, HomesGUI homesGUI, Messages messages) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        homesGUI.open(player, homeManager);
        return true;
    }
}