package fr.quentin.poppy.commands.tpa;

import fr.quentin.poppy.manager.tpa.TpaManager;
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

public class TpaToggleCommand extends SafeCommand {

    private final TpaManager tpaManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public TpaToggleCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages, PoppyConfig config, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
        this.config = config;
        this.cooldown = cooldownManager.get("tpatoggle");
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("tpa.toggle-cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        cooldown.start(player.getUniqueId(), config.tpaToggleCooldownMillis());

        boolean nowAccepting = tpaManager.toggleRequests(player.getUniqueId());
        player.sendMessage(messages.get(nowAccepting ? "tpa.toggle-on" : "tpa.toggle-off"));
        return true;
    }
}