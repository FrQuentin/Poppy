package fr.quentin.poppy.commands;

import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.PoppyStats;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.regex.Pattern;

/**
 * Handles /sethome: creates a new home at the sender's current location, or
 * opens {@link ConfirmOverwriteGUI} if a home with that name already exists.
 *
 * <p>Home names are restricted to {@link #VALID_NAME} because {@link HomeManager}
 * persists them as YAML section keys, and Bukkit's configuration API treats
 * {@code .} as a path separator — an unvalidated name like {@code "my.home"}
 * would silently split into a nested section instead of being stored as one
 * entry, corrupting that player's homes file on the next load.
 */
public class SetHomeCommand extends SafeCommand {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final HomeManager homeManager;
    private final ConfirmOverwriteGUI confirmOverwriteGUI;
    private final PoppyStats stats;
    private final PoppyLogger logger;

    public SetHomeCommand(JavaPlugin plugin, HomeManager homeManager, ConfirmOverwriteGUI confirmOverwriteGUI,
                          Messages messages, PoppyStats stats, PoppyLogger logger) {
        super(plugin, messages);
        this.homeManager = homeManager;
        this.confirmOverwriteGUI = confirmOverwriteGUI;
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
            player.sendMessage(messages.get("sethome.usage"));
            return true;
        }

        String name = args[0];

        if (!VALID_NAME.matcher(name).matches()) {
            player.sendMessage(messages.get("sethome.invalid-name", "home", name));
            return true;
        }

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

        logger.log(PoppyLogger.Category.HOME, player, "created home '" + name + "' at "
                + home.worldName() + ": " + (int) home.x() + ", " + (int) home.y() + ", " + (int) home.z());

        player.sendMessage(messages.get("sethome.success", "home", name));
        return true;
    }
}