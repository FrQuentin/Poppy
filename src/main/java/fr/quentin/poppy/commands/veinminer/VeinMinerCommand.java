package fr.quentin.poppy.commands.veinminer;

import fr.quentin.poppy.manager.veinminer.VeinMinerManager;
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
 * Handles /veinminer: toggles the sender's own veinminer preference (see
 * {@link VeinMinerManager}).
 */
public class VeinMinerCommand extends SafeCommand {

    private final VeinMinerManager veinMinerManager;
    private final PoppyConfig config;
    private final CooldownStore cooldown;

    public VeinMinerCommand(JavaPlugin plugin, VeinMinerManager veinMinerManager, Messages messages,
                            PoppyConfig config, CooldownManager cooldownManager) {
        super(plugin, messages);
        this.veinMinerManager = veinMinerManager;
        this.config = config;
        this.cooldown = cooldownManager.get("veinminer-toggle");
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        long remaining = cooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("veinminer.cooldown", "time", DurationFormat.format(remaining)));
            return true;
        }

        cooldown.start(player.getUniqueId(), config.veinminerToggleCooldownMillis());

        boolean nowEnabled = veinMinerManager.toggle(player.getUniqueId());
        player.sendMessage(messages.get(nowEnabled ? "veinminer.enabled" : "veinminer.disabled"));
        return true;
    }
}