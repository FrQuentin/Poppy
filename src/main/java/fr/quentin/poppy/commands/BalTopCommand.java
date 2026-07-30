package fr.quentin.poppy.commands;

import fr.quentin.poppy.manager.EconomyManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
import fr.quentin.poppy.util.SafeCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Handles /baltop: lists the top {@code baltop-size} balances on the
 * server — same visual format as {@code /cooldowns}
 * ({@code CooldownsCommand}), for a consistent look across the plugin's
 * list-style commands.
 */
public class BalTopCommand extends SafeCommand {

    private final EconomyManager economy;
    private final int size;

    public BalTopCommand(JavaPlugin plugin, EconomyManager economy, Messages messages, int size) {
        super(plugin, messages);
        this.economy = economy;
        this.size = size;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        List<Map.Entry<String, Long>> top = economy.getTopBalances(size);

        if (top.isEmpty()) {
            sender.sendMessage(messages.get("money.baltop-empty"));
            return true;
        }

        sender.sendMessage(messages.get("money.baltop-header"));
        sender.sendMessage(Component.empty());

        int rank = 1;
        for (Map.Entry<String, Long> entry : top) {
            sender.sendMessage(messages.get("money.baltop-line",
                    "rank", String.valueOf(rank),
                    "player", entry.getKey(),
                    "amount", MoneyFormat.format(entry.getValue())));
            rank++;
        }

        return true;
    }
}