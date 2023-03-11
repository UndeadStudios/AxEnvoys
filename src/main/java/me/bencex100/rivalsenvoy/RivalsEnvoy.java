package me.bencex100.rivalsenvoy;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIConfig;
import me.bencex100.rivalsenvoy.commands.Commands;
import me.bencex100.rivalsenvoy.config.Config;
import me.bencex100.rivalsenvoy.listeners.ActivateFlare;
import me.bencex100.rivalsenvoy.listeners.CollectionListener;
import me.bencex100.rivalsenvoy.listeners.FallingBlockListener;
import me.bencex100.rivalsenvoy.utils.EnvoyHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

import static me.bencex100.rivalsenvoy.utils.EnvoyHandler.crates;

public final class RivalsEnvoy extends JavaPlugin {
    private static RivalsEnvoy instance;
    private static EnvoyHandler evh;

    @Override
    public void onEnable() {
        instance = this;
        new Config().loadConfig();
        CommandAPI.onLoad(new CommandAPIConfig());
        CommandAPI.onEnable(this);
        new Commands().register();
        getServer().getPluginManager().registerEvents(new CollectionListener(), this);
        getServer().getPluginManager().registerEvents(new FallingBlockListener(), this);
        getServer().getPluginManager().registerEvents(new ActivateFlare(), this);
        evh = new EnvoyHandler();

    }

    public static RivalsEnvoy getInstance() {
        return instance;
    }

    public static EnvoyHandler getEvh() {
        return evh;
    }

    @Override
    public void onDisable() {
        crates.forEach((key, value) -> value.collectCrate(null));
        CommandAPI.onDisable();
        try {
            Config.getCnf("data").save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
