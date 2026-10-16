package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Live switch for how ridden bones reach their next pose, so snapping and gliding can be compared on the
 * same mount without a reload. It never writes config.yml; the value lasts until FMM reloads.
 *
 * <ul>
 *   <li>{@code /fmm boneglide 0} - ridden bones snap to each pose</li>
 *   <li>{@code /fmm boneglide 1} - ridden bones glide to each pose over one tick</li>
 *   <li>{@code /fmm boneglide}   - report the current value</li>
 * </ul>
 */
public class BoneGlideCommand extends AdvancedCommand {

    public BoneGlideCommand() {
        super(List.of("boneglide"));
        addOptionalArgument("ticks", new ListStringCommandArgument(List.of("0", "1", "2", "3"), "[ticks]"));
        setDescription("Temporarily set how many ticks ridden model bones take to glide to each pose (0 snaps).");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm boneglide [ticks]");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();
        String argument = commandData.getStringArgument("ticks");
        if (argument == null || argument.isBlank()) {
            Logger.sendMessage(sender, "&eRidden bones currently take " + DefaultConfig.riddenBoneInterpolationTicks
                    + " tick(s) per pose (0 snaps). Use /fmm boneglide <ticks> to change it until the next reload.");
            return;
        }
        int ticks;
        try {
            ticks = Integer.parseInt(argument.trim());
        } catch (NumberFormatException invalid) {
            Logger.sendMessage(sender, "&cExpected a number of ticks from 0 to 59.");
            return;
        }
        if (ticks < 0 || ticks > 59) {
            Logger.sendMessage(sender, "&cExpected 0 to 59 ticks.");
            return;
        }
        DefaultConfig.riddenBoneInterpolationTicks = ticks;
        Logger.sendMessage(sender, "&eRidden bones now take " + ticks + " tick(s) per pose"
                + (ticks == 0 ? " (snap)." : ".") + " &7config.yml keeps riddenBoneInterpolationTicks until FMM reloads.");
    }
}
