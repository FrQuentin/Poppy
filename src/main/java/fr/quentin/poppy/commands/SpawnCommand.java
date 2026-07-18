package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SpawnCommand extends SafeCommand {

    private final SpawnManager spawnManager;
    private final TeleportManager teleportManager;

    public SpawnCommand(JavaPlugin plugin, SpawnManager spawnManager, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.spawnManager = spawnManager;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("general.only-player"));
            return true;
        }

        Home spawn = spawnManager.getSpawn();
        if (spawn == null) {
            player.sendMessage(messages.get("spawn.not-set"));
            return true;
        }

        teleportManager.requestTeleport(player, spawn, "spawn.success");
        return true;
    }
}