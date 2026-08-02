package fr.quentin.poppy.commands.tpa;

import fr.quentin.poppy.manager.tpa.TpaManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /tpatoggle: toggles whether the sender accepts incoming /tpa
 * and /tpahere requests at all — see {@link TpaManager#toggleRequests}.
 *
 * <p>Rate-limited via {@code tpa-toggle-cooldown-seconds} in config.yml:
 * each toggle writes {@code tpatoggle.yml} to disk synchronously (see
 * {@link TpaManager}), so without a cooldown this was spammable with no
 * purpose beyond repeatedly triggering that write.
 */
public class TpaToggleCommand extends SafeCommand {

    private final TpaManager tpaManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public TpaToggleCommand(JavaPlugin plugin, TpaManager tpaManager, Messages messages, PoppyConfig config) {
        super(plugin, messages);
        this.tpaManager = tpaManager;
        this.config = config;
        this.cooldown = new CooldownStore(plugin);
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