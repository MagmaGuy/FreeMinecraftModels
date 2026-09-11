package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.PropDebug;
import com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;

import java.util.List;

/**
 * Runtime toggle for the {@code [FMM-BedrockDebug]} log stream. Intentionally
 * NOT a config option — see {@link BedrockDebugLog} class javadoc for why.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /fmm debug bedrock on}  — flip diagnostic logging on</li>
 *   <li>{@code /fmm debug bedrock off} — flip it back off</li>
 *   <li>{@code /fmm debug bedrock}     — report current state without changing it</li>
 * </ul>
 *
 * <p>The first {@code <subsystem>} argument is named {@code bedrock} (not
 * just absent) so we leave room for future debug toggles ({@code particles},
 * {@code packets}, etc.) without renaming the command. Supported today:
 * {@code bedrock} and {@code props} (stacked-props proximity scan); unknown
 * subsystems print usage.</p>
 */
public class BedrockDebugCommand extends AdvancedCommand {

    public BedrockDebugCommand() {
        super(List.of("debug"));
        addArgument("subsystem", new ListStringCommandArgument(
                List.of("bedrock", "props"),
                "<subsystem>"));
        addArgument("state", new ListStringCommandArgument(
                List.of("on", "off"),
                "[on|off]"));
        setDescription("Toggle FMM runtime diagnostic logging (Bedrock display pipeline, stacked-props scan).");
        // Same permission shape as the other admin/debug commands in this package
        // — anyone with the global FMM wildcard or an explicit grant can flip it.
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm debug <bedrock|props> [on|off]");
        // Console + player both make sense — debug toggles are usually flipped
        // from the console while tailing the log, but in-game admins might too.
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        String subsystem = commandData.getStringArgument("subsystem");
        String state = commandData.getStringArgument("state");

        boolean bedrock = "bedrock".equalsIgnoreCase(subsystem);
        boolean props = "props".equalsIgnoreCase(subsystem);
        if (!bedrock && !props) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "Usage: /fmm debug <bedrock|props> [on|off]");
            return;
        }
        String label = bedrock ? "Bedrock display debug logging" : "Stacked-props diagnostic scan";

        if (state == null || state.isBlank()) {
            // No state argument → report current state, don't mutate.
            boolean current = bedrock ? BedrockDebugLog.enabled() : PropDebug.enabled();
            Logger.sendMessage(commandData.getCommandSender(),
                    label + " is currently " + (current ? "ON" : "OFF")
                            + ". Use /fmm debug " + subsystem.toLowerCase() + " on|off to change.");
            return;
        }

        boolean target;
        switch (state.toLowerCase()) {
            case "on", "true", "enable", "enabled" -> target = true;
            case "off", "false", "disable", "disabled" -> target = false;
            default -> {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Unknown state '" + state + "'. Expected 'on' or 'off'.");
                return;
            }
        }

        boolean actual = bedrock ? BedrockDebugLog.setEnabled(target) : PropDebug.setEnabled(target);
        String detail = bedrock
                ? "Log lines prefixed with [FMM-BedrockDebug]. "
                + (actual ? "Reproduce the issue then turn this OFF — it's verbose." : "")
                : (actual
                ? "Chunk loads now report any two props within 0.1 blocks of each other"
                + " (\"STACKED PROPS DETECTED\")."
                : "");
        Logger.sendMessage(commandData.getCommandSender(),
                label + " is now " + (actual ? "ON" : "OFF") + ". " + detail);
    }
}
