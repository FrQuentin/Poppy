package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SetHomeCommand extends SafeCommand {

    private final HomeManager homeManager;
    private final ConfirmOverwriteGUI confirmOverwriteGUI;
    private final PoppyStats stats;

    public SetHomeCommand(JavaPlugin plugin, HomeManager homeManager, ConfirmOverwriteGUI confirmOverwriteGUI, Messages messages, PoppyStats stats) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.confirmOverwriteGUI = confirmOverwriteGUI;
        this.stats = stats;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("sethome.usage"));
            return true;
        }

        String name = args[0];

        if (homeManager.hasHome(player.getUniqueId(), name)) {
            Home pending = Home.fromLocation(name, player.getLocation());
            confirmOverwriteGUI.open(player, pending);
            return true;
        }

        if (homeManager.isFull(player.getUniqueId())) {
            player.sendMessage(messages.get("sethome.full", "max", String.valueOf(HomeManager.MAX_HOMES)));
            return true;
        }

        Home home = Home.fromLocation(name, player.getLocation());
        homeManager.addHome(player.getUniqueId(), home);
        stats.incrementHomesCreated();

        player.sendMessage(messages.get("sethome.success", "home", name));
        return true;
    }
}