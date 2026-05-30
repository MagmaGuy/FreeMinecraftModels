package com.magmaguy.freeminecraftmodels.commands;

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
 * {@code packets}, etc.) without renaming the command. Today only
 * {@code bedrock} is supported; unknown subsystems print usage.</p>
 */
public class BedrockDebugCommand extends AdvancedCommand {

    public BedrockDebugCommand() {
        super(List.of("debug"));
        addArgument("subsystem", new ListStringCommandArgument(
                List.of("bedrock"),
                "<subsystem>"));
        addArgument("state", new ListStringCommandArgument(
                List.of("on", "off"),
                "[on|off]"));
        setDescription("Toggle FMM runtime diagnostic logging (e.g. Bedrock display pipeline).");
        // Same permission shape as the other admin/debug commands in this package
        // — anyone with the global FMM wildcard or an explicit grant can flip it.
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm debug bedrock [on|off]");
        // Console + player both make sense — debug toggles are usually flipped
        // from the console while tailing the log, but in-game admins might too.
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        String subsystem = commandData.getStringArgument("subsystem");
        String state = commandData.getStringArgument("state");

        if (subsystem == null || subsystem.isBlank() || !"bedrock".equalsIgnoreCase(subsystem)) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "Usage: /fmm debug bedrock [on|off] — currently the only supported subsystem is 'bedrock'.");
            return;
        }

        if (state == null || state.isBlank()) {
            // No state argument → report current state, don't mutate.
            Logger.sendMessage(commandData.getCommandSender(),
                    "Bedrock display debug logging is currently "
                            + (BedrockDebugLog.enabled() ? "ON" : "OFF")
                            + ". Use /fmm debug bedrock on|off to change.");
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

        boolean actual = BedrockDebugLog.setEnabled(target);
        Logger.sendMessage(commandData.getCommandSender(),
                "Bedrock display debug logging is now " + (actual ? "ON" : "OFF")
                        + ". Log lines prefixed with [FMM-BedrockDebug]. "
                        + (actual
                            ? "Reproduce the issue then turn this OFF — it's verbose."
                            : ""));
    }
}
