package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SetSpawnCommand extends SafeCommand {

    private final SpawnManager spawnManager;

    public SetSpawnCommand(JavaPlugin plugin, SpawnManager spawnManager, Messages messages) {
        super(plugin, messages);
        this.spawnManager = spawnManager;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        spawnManager.setSpawn(player.getLocation());
        player.sendMessage(messages.get("spawn.set"));
        return true;
    }
}