package fr.quentin.poppy;

import fr.quentin.poppy.commands.BackCommand;
import fr.quentin.poppy.commands.DelHomeCommand;
import fr.quentin.poppy.commands.HomeCommand;
import fr.quentin.poppy.commands.HomesCommand;
import fr.quentin.poppy.commands.PoppyGotoCommand;
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

public final class Poppy extends JavaPlugin {

    private HomeManager homeManager;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        homeManager = new HomeManager(this);
        messages = new Messages(this);
        HomesGUI homesGUI = new HomesGUI(this, messages);
        ConfirmDeleteGUI confirmDeleteGUI = new ConfirmDeleteGUI(this, messages);
        ConfirmOverwriteGUI confirmOverwriteGUI = new ConfirmOverwriteGUI(this, messages);
        BackManager backManager = new BackManager();
        TeleportManager teleportManager = new TeleportManager(this, messages, backManager);
        SpawnManager spawnManager = new SpawnManager(this);
        ShareManager shareManager = new ShareManager(this);

        getCommand("sethome").setExecutor(new SetHomeCommand(homeManager, confirmOverwriteGUI, messages));

        HomeCommand homeCommand = new HomeCommand(homeManager, homesGUI, teleportManager, messages);
        getCommand("home").setExecutor(homeCommand);
        getCommand("home").setTabCompleter(homeCommand);

        DelHomeCommand delHomeCommand = new DelHomeCommand(homeManager, messages);
        getCommand("delhome").setExecutor(delHomeCommand);
        getCommand("delhome").setTabCompleter(delHomeCommand);

        getCommand("homes").setExecutor(new HomesCommand(homeManager, homesGUI, messages));

        getCommand("setspawn").setExecutor(new SetSpawnCommand(spawnManager, messages));
        getCommand("spawn").setExecutor(new SpawnCommand(spawnManager, teleportManager, messages));

        ShareHomeCommand shareHomeCommand = new ShareHomeCommand(homeManager, shareManager, messages);
        getCommand("sharehome").setExecutor(shareHomeCommand);
        getCommand("sharehome").setTabCompleter(shareHomeCommand);
        getCommand("poppygoto").setExecutor(new PoppyGotoCommand(shareManager, teleportManager, messages));

        getCommand("back").setExecutor(new BackCommand(backManager, teleportManager, messages));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager), this);

        getLogger().info("Poppy has been enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Poppy has been disabled.");
    }
}