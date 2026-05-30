package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class FreeMinecraftModelsFirstTimeSetupWarner implements Listener {
    private final JavaPlugin ownerPlugin;

    public FreeMinecraftModelsFirstTimeSetupWarner(JavaPlugin ownerPlugin) {
        this.ownerPlugin = ownerPlugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (DefaultConfig.isSetupDone()) return;
        if (!event.getPlayer().hasPermission("freeminecraftmodels.*")) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!event.getPlayer().isOnline()) return;
                if (DefaultConfig.isSetupDone()) return;

                Logger.sendSimpleMessage(event.getPlayer(), "&8&m----------------------------------------------------");
                Logger.sendMessage(event.getPlayer(), "&fFirst boot message:");
                Logger.sendSimpleMessage(event.getPlayer(), "&7It looks like this is your first boot. &aFreeMinecraftModels is ready to use.");
                event.getPlayer().spigot().sendMessage(
                        SpigotMessage.commandHoverMessage("&a/fmm setup",
                                "&7Browse and install premade FreeMinecraftModels content.",
                                "/fmm setup"),
                        SpigotMessage.simpleMessage("&7 opens premade content and permanently dismisses this message."));
                event.getPlayer().spigot().sendMessage(
                        SpigotMessage.commandHoverMessage("&a/fmm admin",
                                "&7Browse installed FreeMinecraftModels content.",
                                "/fmm admin"),
                        SpigotMessage.simpleMessage("&7 shows installed content, and "),
                        SpigotMessage.commandHoverMessage("&a/fmm",
                                "&7Open the craftable items browser.",
                                "/fmm"),
                        SpigotMessage.simpleMessage("&7 shows recipes players can craft."));
                Logger.sendSimpleMessage(event.getPlayer(), "&8&m----------------------------------------------------");
            }
        }.runTaskLater(ownerPlugin, 20L * 10);
    }
}
