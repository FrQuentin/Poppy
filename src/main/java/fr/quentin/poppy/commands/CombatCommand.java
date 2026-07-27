package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.CombatManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /combat: shows the sender how much combat-tag time they have
 * left, if any — see {@link CombatManager}.
 */
public class CombatCommand extends SafeCommand {

    private final CombatManager combatManager;

    public CombatCommand(JavaPlugin plugin, CombatManager combatManager, Messages messages) {
        super(plugin, messages);
        this.combatManager = combatManager;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = combatManager.remainingSeconds(player.getUniqueId());
        if (remaining <= 0) {
            player.sendMessage(messages.get("combat.not-in-combat"));
            return true;
        }

        player.sendMessage(messages.get("combat.remaining", "time", DurationFormat.format(remaining)));
        return true;
    }
}