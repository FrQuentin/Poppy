package fr.quentin.poppy.commands.home;

import fr.quentin.poppy.gui.home.HomesGUI;
import fr.quentin.poppy.manager.home.HomeManager;
import fr.quentin.poppy.manager.teleport.TeleportManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * Handles /home: with no argument, opens the same homes GUI as /homes
 * (see {@link HomesGUI}); with a name, teleports directly to that home.
 *
 * <p>The no-argument path re-checks {@code poppy.homes} — the same
 * permission {@code HomesCommand} enforces — before opening the GUI.
 * Without this check, a player who had {@code poppy.home} but had
 * {@code poppy.homes} specifically revoked could still reach the exact
 * same GUI through this command, silently bypassing the restriction
 * {@code /homes} was supposed to enforce.
 */
public class HomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final HomesGUI homesGUI;
    private final TeleportManager teleportManager;

    public HomeCommand(JavaPlugin plugin, HomeManager homeManager, HomesGUI homesGUI, TeleportManager teleportManager, Messages messages) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.homesGUI = homesGUI;
        this.teleportManager = teleportManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length == 0) {
            // Same permission check as /homes — without this, a player
            // missing poppy.homes (but keeping poppy.home) could still
            // reach the GUI through this exact path.
            if (!player.hasPermission("poppy.homes")) {
                player.sendMessage(messages.get("general.no-permission"));
                return true;
            }
            homesGUI.open(player, homeManager);
            return true;
        }

        String name = args[0];
        if (!homeManager.hasHome(player.getUniqueId(), name)) {
            player.sendMessage(messages.get("home.not-found"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        teleportManager.requestTeleport(player, () -> homeManager.getHome(uuid, name), "home.success");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        return homeManager.suggestHomeNames(player.getUniqueId(), args[0]);
    }
}