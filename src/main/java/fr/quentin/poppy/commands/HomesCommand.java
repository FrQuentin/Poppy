package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class HomesCommand extends SafeCommand {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;

    public HomesCommand(JavaPlugin plugin, HomeManager homeManager, HomesGUI homesGUI, Messages messages) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        homesGUI.open(player, homeManager);
        return true;
    }
}