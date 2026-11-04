package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.core.SlowViewers;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Live switch for how often ridden bones send their idle and walk poses, so every tick and every other tick can be
 * compared on the same models without a reload. It never writes config.yml; the setting lasts until FMM reloads.
 *
 * <ul>
 *   <li>{@code /fmm poserate 1}    - every model sends idle and walk poses every tick, to everyone</li>
 *   <li>{@code /fmm poserate 2}    - every model sends them every other tick and glides over both, to everyone</li>
 *   <li>{@code /fmm poserate auto} - back to config.yml, where models slow down only while a slow game watches</li>
 *   <li>{@code /fmm poserate}      - report the current setting</li>
 * </ul>
 */
public class PoseRateCommand extends AdvancedCommand {

    public PoseRateCommand() {
        super(List.of("poserate"));
        addOptionalArgument("ticks", new ListStringCommandArgument(List.of("auto", "1", "2", "3"), "[ticks|auto]"));
        setDescription("Temporarily make every model send idle and walk poses every N ticks, or 'auto' for config.yml.");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm poserate [ticks|auto]");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();
        String argument = commandData.getStringArgument("ticks");
        if (argument == null || argument.isBlank()) {
            int forced = SlowViewers.forcedInterval();
            if (forced > 0)
                Logger.sendMessage(sender, "&eEvery model sends idle and walk poses every " + forced
                        + " tick(s), whoever watches. Use /fmm poserate auto to return to config.yml.");
            else
                Logger.sendMessage(sender, "&eModels follow config.yml: idle and walk poses every tick, or every "
                        + DefaultConfig.riddenBoneUpdateIntervalTicks + " ticks while a game under "
                        + DefaultConfig.riddenBoneUpdateIntervalSlowClientFps
                        + " fps watches. Use /fmm poserate <ticks> to force a rate until the next reload.");
            return;
        }
        if (argument.trim().equalsIgnoreCase("auto")) {
            SlowViewers.forceInterval(0);
            Logger.sendMessage(sender, "&eModels follow config.yml again.");
            return;
        }
        int ticks;
        try {
            ticks = Integer.parseInt(argument.trim());
        } catch (NumberFormatException invalid) {
            Logger.sendMessage(sender, "&cExpected a number of ticks of at least 1, or auto.");
            return;
        }
        if (ticks < 1) {
            Logger.sendMessage(sender, "&cExpected a number of ticks of at least 1, or auto.");
            return;
        }
        SlowViewers.forceInterval(ticks);
        Logger.sendMessage(sender, "&eEvery model now sends idle and walk poses every " + ticks
                + " tick(s), whoever watches. &7Attacks and other animations still send every tick. config.yml"
                + " applies again after /fmm poserate auto or a reload.");
    }
}
