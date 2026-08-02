package fr.quentin.poppy.commands.trash;

import fr.quentin.poppy.gui.trash.TrashGUI;
import fr.quentin.poppy.gui.trash.TrashListener;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /trash: opens a virtual trash can. Anything left inside when the
 * player closes it is deleted — see {@link TrashListener}.
 */
public class TrashCommand extends SafeCommand {

    private final TrashGUI trashGUI;

    public TrashCommand(JavaPlugin plugin, TrashGUI trashGUI, Messages messages) {
        super(plugin, messages);
        this.trashGUI = trashGUI;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        trashGUI.open(player);
        return true;
    }
}