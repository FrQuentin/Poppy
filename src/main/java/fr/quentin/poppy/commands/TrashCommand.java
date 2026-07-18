package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.TrashGUI;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class TrashCommand extends SafeCommand {

    private final TrashGUI trashGUI;

    public TrashCommand(JavaPlugin plugin, TrashGUI trashGUI, Messages messages) {
        super(plugin, messages);
        this.trashGUI = trashGUI;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        trashGUI.open(player);
        return true;
    }
}