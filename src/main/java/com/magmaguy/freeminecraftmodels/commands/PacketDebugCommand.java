package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.debug.PacketDiagnostics;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * One-shot diagnostic that samples the modeled-entity packet load for the next
 * N model-clock ticks and prints what every viewer is actually being sent.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /fmm packetdebug}      — sample the next single tick</li>
 *   <li>{@code /fmm packetdebug 20}   — sample the next 20 ticks (~1s) and average</li>
 * </ul>
 *
 * <p>Run it while standing in the laggy area (e.g. the NPC hub). It reports
 * logical packets/tick, packets delivered/tick (counting per-viewer fan-out),
 * the worst single player/tick, a packet-type breakdown, and the heaviest
 * viewers. Packet counts are exact; byte figures are rough estimates. See
 * {@link PacketDiagnostics} for the why.</p>
 */
public class PacketDebugCommand extends AdvancedCommand {

    private static final int MAX_TICKS = 200;       // cap the sample window at ~10s
    private static final int POLL_LIMIT_TICKS = 400; // give up waiting after ~20s of server ticks

    public PacketDebugCommand() {
        super(List.of("packetdebug"));
        addOptionalArgument("ticks", new ListStringCommandArgument(
                List.of("1", "5", "20", "60"),
                "[ticks]"));
        setDescription("Sample modeled-entity packet load for the next tick(s) and report per-player packet counts.");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm packetdebug [ticks]");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();

        int ticks = 1;
        String ticksArg = commandData.getStringArgument("ticks");
        if (ticksArg != null && !ticksArg.isBlank()) {
            try {
                ticks = Integer.parseInt(ticksArg.trim());
            } catch (NumberFormatException e) {
                Logger.sendMessage(sender, "&cInvalid tick count '" + ticksArg + "'. Using 1.");
                ticks = 1;
            }
        }
        if (ticks < 1) ticks = 1;
        if (ticks > MAX_TICKS) ticks = MAX_TICKS;

        final java.util.concurrent.CompletableFuture<PacketDiagnostics.Sample> requested;
        try {
            requested = PacketDiagnostics.arm(ticks);
        } catch (IllegalStateException busy) {
            Logger.sendMessage(sender, "&e" + busy.getMessage());
            return;
        }
        Logger.sendMessage(sender, "&eSampling modeled-entity packets for the next " + ticks
                + " tick(s)... stand in the laggy area.");

        final int[] polls = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                polls[0] += 2;
                if (requested.isCancelled()) {
                    cancel();
                    Logger.sendMessage(sender, "&ePacket sample cancelled because the model clock stopped.");
                    return;
                }
                PacketDiagnostics.Sample latest = requested.getNow(null);
                if (latest != null) {
                    cancel();
                    latest.toLines().forEach(line -> Logger.sendMessage(sender, line));
                    return;
                }
                if (polls[0] >= POLL_LIMIT_TICKS) {
                    requested.cancel(false);
                    cancel();
                    Logger.sendMessage(sender, "&cPacket sample timed out — is the model clock running? "
                            + "(No modeled entities loaded, or NMS disabled.)");
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 2L, 2L);
    }
}
