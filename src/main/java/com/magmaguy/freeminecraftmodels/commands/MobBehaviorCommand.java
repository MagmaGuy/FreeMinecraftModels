package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.mobbehaviors.MobBehaviors;
import com.magmaguy.magmacore.ai.overlay.MindOverlayRequest;
import com.magmaguy.magmacore.ai.overlay.MindOverlays;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.DoubleCommandArgument;
import com.magmaguy.magmacore.command.arguments.IntegerCommandArgument;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Puts mob behaviors on mobs and takes them off, for trying a behavior out and for admins:
 * {@code apply <behaviors> [seconds] [radius]} lays the named behaviors, separated by commas, over the AI of the mob
 * you look at, or of every mob within the radius, with you as their owner, for that many seconds or until cleared;
 * {@code applywild} does the same with no owner, so a hostile or cowardly mob treats you like any other player;
 * {@code clear} gives the mob you look at its own AI back, and {@code clear all} every mob; {@code list} shows the
 * behaviors that loaded and how many mobs run one.
 */
public class MobBehaviorCommand extends AdvancedCommand {
    /** How far along your line of sight the command looks for a mob, in blocks. */
    private static final double LOOK_DISTANCE = 32;

    public MobBehaviorCommand() {
        super(List.of("mobbehavior"));
        addArgument("action",
                new ListStringCommandArgument(List.of("apply", "applywild", "clear", "list"), "<action>"));
        addOptionalArgument("behaviors", new BehaviorsArgument());
        addOptionalArgument("seconds", new IntegerCommandArgument("[seconds]"));
        addOptionalArgument("radius", new DoubleCommandArgument("[radius]"));
        setUsage("/fmm mobbehavior <apply|applywild|clear|list> [behaviors|all] [seconds] [radius]");
        setPermission("freeminecraftmodels.mobbehaviors.admin");
        setSenderType(SenderType.PLAYER);
        setDescription("Overrides the AI of the mob you look at with Lua mob behaviors, or gives it back.");
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        MindOverlays overlays = MobBehaviors.get();
        if (overlays == null) {
            Logger.sendMessage(player, "&cMob behaviors are not available on this server: they need Minecraft"
                    + " 1.21.4 or newer.");
            return;
        }
        String named = commandData.getStringArgument("behaviors");
        switch (commandData.getStringArgument("action").toLowerCase(Locale.ROOT)) {
            case "apply" -> apply(player, overlays, named,
                    commandData.getIntegerArgument("seconds"), commandData.getDoubleArgument("radius"), true);
            case "applywild" -> apply(player, overlays, named,
                    commandData.getIntegerArgument("seconds"), commandData.getDoubleArgument("radius"), false);
            case "clear" -> clear(player, overlays, named);
            default -> Logger.sendMessage(player, "&7Mob behaviors: &f"
                    + (overlays.behaviors().isEmpty() ? "none" : String.join("&7, &f", overlays.behaviors()))
                    + "&7. Mobs running one: &f" + overlays.overlaidMobs().size());
        }
    }

    /** @param owned whether the player becomes the mobs' owner, whom they serve and never hurt */
    private static void apply(Player player, MindOverlays overlays, String named, Integer seconds, Double radius,
                              boolean owned) {
        if (named == null) {
            Logger.sendMessage(player, "&cName the behaviors to put on the mob, separated by commas: &f"
                    + String.join(", ", overlays.behaviors()));
            return;
        }
        MindOverlayRequest request;
        try {
            request = MindOverlayRequest.of(Arrays.asList(named.split(",")))
                    .ownedBy(owned ? player.getUniqueId() : null)
                    .lasting(seconds == null ? 0L : Math.max(0L, seconds) * 20L);
        } catch (IllegalArgumentException exception) {
            Logger.sendMessage(player, "&c" + exception.getMessage());
            return;
        }
        List<LivingEntity> mobs = targets(player, radius);
        if (mobs.isEmpty()) {
            Logger.sendMessage(player, radius == null ? "&cLook at a mob, or give a radius."
                    : "&cThere is no mob within " + radius + " blocks.");
            return;
        }
        int applied = 0;
        String problem = null;
        for (LivingEntity mob : mobs) {
            String refused = overlays.apply(mob, request);
            if (refused == null) applied++;
            else problem = refused;
        }
        if (applied > 0) {
            Logger.sendMessage(player, "&a" + applied + (applied == 1 ? " mob now runs &f" : " mobs now run &f")
                    + String.join(", ", request.behaviors())
                    + (request.durationTicks() > 0 ? "&a for " + seconds + " seconds." : "&a until cleared."));
        }
        if (problem != null) Logger.sendMessage(player, "&c" + (mobs.size() - applied) + " refused: " + problem);
    }

    private static void clear(Player player, MindOverlays overlays, String named) {
        if ("all".equalsIgnoreCase(named)) {
            Logger.sendMessage(player, "&a" + overlays.clearAll() + " mobs have their own AI back.");
            return;
        }
        List<LivingEntity> mobs = targets(player, null);
        if (mobs.isEmpty()) {
            Logger.sendMessage(player, "&cLook at a mob, or use &f/fmm mobbehavior clear all&c.");
            return;
        }
        Logger.sendMessage(player, overlays.clear(mobs.get(0)) ? "&aThat mob has its own AI back."
                : "&7That mob runs no mob behavior.");
    }

    /** The mob the player looks at, or every mob within the radius; never a player. */
    private static List<LivingEntity> targets(Player player, Double radius) {
        List<LivingEntity> mobs = new ArrayList<>();
        if (radius != null) {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity instanceof LivingEntity living && !(entity instanceof Player)) mobs.add(living);
            }
            return mobs;
        }
        RayTraceResult hit = player.getWorld().rayTraceEntities(player.getEyeLocation(),
                player.getEyeLocation().getDirection(), LOOK_DISTANCE, 0.3,
                entity -> entity instanceof LivingEntity && !(entity instanceof Player));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity living) mobs.add(living);
        return mobs;
    }

    /**
     * Behavior names separated by commas, completed one name at a time, or {@code all} for clear. Any text is
     * taken, so a name that did not load is answered with the ones that did rather than with the usage.
     */
    private static final class BehaviorsArgument extends ListStringCommandArgument {
        private BehaviorsArgument() {
            super("[behaviors|all]");
        }

        @Override
        public boolean matchesInput(String input) {
            return !input.isEmpty();
        }

        @Override
        public List<String> getSuggestions(CommandSender sender, String partialInput) {
            MindOverlays overlays = MobBehaviors.get();
            if (overlays == null) return List.of();
            int comma = partialInput.lastIndexOf(',');
            String before = partialInput.substring(0, comma + 1);
            String typing = partialInput.substring(comma + 1).toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String behavior : overlays.behaviors()) {
                if (behavior.startsWith(typing)) suggestions.add(before + behavior);
            }
            if (before.isEmpty() && "all".startsWith(typing)) suggestions.add("all");
            return suggestions;
        }
    }
}
