package fr.quentin.poppy;

import fr.quentin.poppy.commands.*;
import fr.quentin.poppy.gui.*;
import fr.quentin.poppy.listeners.JoinQuitListener;
import fr.quentin.poppy.listeners.TabHealthListener;
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
        TrashGUI trashGUI = new TrashGUI(this, messages);

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

        Objects.requireNonNull(getCommand("trash")).setExecutor(new TrashCommand(this, trashGUI, messages));

        getServer().getPluginManager().registerEvents(
                new HomesGUIListener(this, homeManager, homesGUI, confirmDeleteGUI, confirmOverwriteGUI, teleportManager, messages), this);
        getServer().getPluginManager().registerEvents(teleportManager, this);
        getServer().getPluginManager().registerEvents(new BackListener(this, backManager), this);
        getServer().getPluginManager().registerEvents(new TrashListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this, messages), this);
        getServer().getPluginManager().registerEvents(new TabHealthListener(this), this);

        getLogger().info("Poppy has been enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Poppy has been disabled.");
    }
}