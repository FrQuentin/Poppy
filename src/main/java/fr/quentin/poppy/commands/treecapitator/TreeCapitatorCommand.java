package fr.quentin.poppy.commands.treecapitator;

import fr.quentin.poppy.manager.treecapitator.TreeCapitatorManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /treecapitator: toggles the sender's own treecapitator
 * preference (see {@link TreeCapitatorManager}).
 */
public class TreeCapitatorCommand extends SafeCommand {

    private final TreeCapitatorManager treeCapitatorManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public TreeCapitatorCommand(JavaPlugin plugin, TreeCapitatorManager treeCapitatorManager, Messages messages,
                                PoppyConfig config, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.treeCapitatorManager = treeCapitatorManager;
        this.config = config;
        this.cooldown = cooldownManager.get("treecapitator-toggle");
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("treecapitator.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        cooldown.start(player.getUniqueId(), config.treecapitatorToggleCooldownMillis());

        boolean nowEnabled = treeCapitatorManager.toggle(player.getUniqueId());
        player.sendMessage(messages.get(nowEnabled ? "treecapitator.enabled" : "treecapitator.disabled"));
        return true;
    }
}