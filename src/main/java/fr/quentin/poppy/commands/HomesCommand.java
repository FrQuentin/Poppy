package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HomesCommand implements CommandExecutor {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final Messages messages;

    public HomesCommand(HomeManager homeManager, HomesGUI homesGUI, Messages messages) {
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        homesGUI.open(player, homeManager);
        return true;
    }
}
