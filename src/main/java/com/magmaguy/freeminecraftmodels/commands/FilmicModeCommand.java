package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.core.FilmicMode;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Temporary toggle for filmic mode. It never writes config.yml; see {@link FilmicMode}
 * for how long the toggle lasts.
 *
 * <ul>
 *   <li>{@code /fmm filmic on}  - show every loaded model in each player's loaded chunks</li>
 *   <li>{@code /fmm filmic off} - return to normal view-distance and line-of-sight culling</li>
 *   <li>{@code /fmm filmic}     - report the current state</li>
 * </ul>
 */
public class FilmicModeCommand extends AdvancedCommand {

    public FilmicModeCommand() {
        super(List.of("filmic"));
        addOptionalArgument("state", new ListStringCommandArgument(
                List.of("on", "off"),
                "[on|off]"));
        setDescription("Temporarily show every loaded model, even out of sight, for ReplayMod recordings. Not recommended.");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm filmic [on|off]");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();
        String state = commandData.getStringArgument("state");

        if (state == null || state.isBlank()) {
            Logger.sendMessage(sender, "&eFilmic mode is currently " + describeState()
                    + ". Use /fmm filmic on|off to change it temporarily.");
            return;
        }

        boolean target;
        switch (state.toLowerCase()) {
            case "on", "true", "enable", "enabled" -> target = true;
            case "off", "false", "disable", "disabled" -> target = false;
            default -> {
                Logger.sendMessage(sender, "&cUnknown state '" + state + "'. Expected 'on' or 'off'.");
                return;
            }
        }

        FilmicMode.setFromCommand(target);
        Logger.sendMessage(sender, "&eFilmic mode is now " + describeState() + ".");
        if (target) {
            Logger.sendMessage(sender, "&c&lWarning: not recommended outside of recording! &cEvery loaded model is now"
                    + " sent to every player who has its chunk loaded, even behind walls or past the view distance."
                    + " This costs a lot of bandwidth and server performance.");
        } else {
            Logger.sendMessage(sender, "&aModels out of sight or past the view distance are hidden again.");
        }
        if (FilmicMode.isCommandOverrideActive()) {
            Logger.sendMessage(sender, "&7config.yml still has filmicMode: " + DefaultConfig.filmicMode
                    + ". This change lasts until the server restarts or that option is changed and FMM reloads.");
        }
    }

    private static String describeState() {
        String onOff = FilmicMode.isEnabled() ? "ON" : "OFF";
        return FilmicMode.isCommandOverrideActive()
                ? onOff + " (temporary, set by /fmm filmic)"
                : onOff + " (from config.yml)";
    }
}
