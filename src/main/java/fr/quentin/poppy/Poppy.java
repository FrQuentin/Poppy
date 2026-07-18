package fr.quentin.poppy;

import fr.quentin.poppy.commands.BackCommand;
import fr.quentin.poppy.commands.DelHomeCommand;
import fr.quentin.poppy.commands.HomeCommand;
import fr.quentin.poppy.commands.HomesCommand;
import fr.quentin.poppy.commands.PoppyGotoCommand;
import fr.quentin.poppy.commands.RtpCommand;
import fr.quentin.poppy.commands.SetHomeCommand;
import fr.quentin.poppy.commands.SetSpawnCommand;
import fr.quentin.poppy.commands.ShareHomeCommand;
import fr.quentin.poppy.commands.SpawnCommand;
import fr.quentin.poppy.gui.ConfirmDeleteGUI;
import fr.quentin.poppy.gui.ConfirmOverwriteGUI;
import fr.quentin.poppy.gui.HomesGUI;
import fr.quentin.poppy.gui.HomesGUIListener;
import fr.quentin.poppy.manager.BackListener;
import fr.quentin.poppy.manager.BackManager;
import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.manager.ShareManager;
import fr.quentin.poppy.manager.SpawnManager;
import fr.quentin.poppy.manager.TeleportManager;
import fr.quentin.poppy.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Poppy extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        HomeManager homeManager = new HomeManager(this);
        Messages messages = new Messages(this);
        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        TeleportManager teleportManager = new TeleportManager(this, messages, backManager);
        SpawnManager spawnManager = new SpawnManager(this);
        ShareManager shareManager = new ShareManager(this);

        Objects.requireNonNull(getCommand("sethome")).setExecutor(new SetHomeCommand(this, homeManager, confirmOverwriteGUI, messages));

        HomeCommand homeCommand = new HomeCommand(this, homeManager, homesGUI, teleportManager, messages);
        Objects.requireNonNull(getCommand("home")).setExecutor(homeCommand);
        Objects.requireNonNull(getCommand("home")).setTabCompleter(homeCommand);

        DelHomeCommand delHomeCommand = new DelHomeCommand(this, homeManager, messages);
        Objects.requireNonNull(getCommand("delhome")).setExecutor(delHomeCommand);
        Objects.requireNonNull(getCommand("delhome")).setTabCompleter(delHomeCommand);

        Objects.requireNonNull(getCommand("homes")).setExecutor(new HomesCommand(this, homeManager, homesGUI, messages));

        Objects.requireNonNull(getCommand("setspawn")).setExecutor(new SetSpawnCommand(this, spawnManager, messages));
        Objects.requireNonNull(getCommand("spawn")).setExecutor(new SpawnCommand(this, spawnManager, teleportManager, messages));

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(this, homeManager, shareManager, messages);
        Objects.requireNonNull(getCommand("sharehome")).setExecutor(shareHomeCommand);
        Objects.requireNonNull(getCommand("sharehome")).setTabCompleter(shareHomeCommand);
        Objects.requireNonNull(getCommand("poppygoto")).setExecutor(new PoppyGotoCommand(this, shareManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("back")).setExecutor(new BackCommand(this, backManager, teleportManager, messages));

        Objects.requireNonNull(getCommand("rtp")).setExecutor(new RtpCommand(this, teleportManager, messages));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(this, homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager), this);

        getLogger().info("Poppy has been enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Poppy has been disabled.");
    }
}