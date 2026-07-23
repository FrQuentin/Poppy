package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /delhome: deletes one of the sender's homes by name.
 * Also reachable via the /homes GUI (right-click + confirm), which calls
 * {@link HomeManager} directly rather than going through this class.
 */
public class DelHomeCommand extends SafeCommand implements TabCompleter {

    private final HomeManager homeManager;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    public DelHomeCommand(JavaPlugin plugin, HomeManager homeManager, Messages messages, PoppyStats stats, PoppyLogger logger) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.stats = stats;
        this.logger = logger;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(messages.get("delhome.usage"));
            return true;
        }

        String name = args[0];
        Home home = homeManager.getHome(player.getUniqueId(), name);

        if (home == null) {
            player.sendMessage(messages.get("delhome.not-found", "home", name));
            return true;
        }

        homeManager.removeHome(player.getUniqueId(), name);
        stats.incrementHomesDeleted();

        logger.log(PoppyLogger.Category.HOME, player, "deleted home '" + home.name() + "'");

        player.sendMessage(messages.get("delhome.success", "home", home.name()));
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