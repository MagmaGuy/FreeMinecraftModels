package com.magmaguy.freeminecraftmodels.commands;

import cloud.commandframework.ArgumentDescription;
import cloud.commandframework.Command;
import cloud.commandframework.CommandTree;
import cloud.commandframework.arguments.standard.StringArgument;
import cloud.commandframework.bukkit.BukkitCommandManager;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.meta.CommandMeta;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.minecraft.extras.MinecraftHelp;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static net.kyori.adventure.text.Component.text;

public class CommandHandler {
    private BukkitCommandManager<CommandSender> manager;
    private MinecraftHelp<CommandSender> minecraftHelp;
    private BukkitAudiences bukkitAudiences;

    public CommandHandler() {
        Function<CommandTree, CommandExecutionCoordinator> commandExecutionCoordinator = null;
        try {
            Class<?> c = Class.forName("cloud.commandframework.execution.CommandExecutionCoordinator");
            Method method = c.getDeclaredMethod("simpleCoordinator");
            commandExecutionCoordinator = (Function<CommandTree, CommandExecutionCoordinator>) method.invoke(Function.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            manager = new BukkitCommandManager(
                    /* Owning plugin */ MetadataHandler.PLUGIN,
                    /* Coordinator function */ commandExecutionCoordinator,
                    /* Command Sender -> C */ Function.identity(),
                    /* C -> Command Sender */ Function.identity()
            );
        } catch (final Exception e) {
             Developer.warn("Failed to initialize the command manager");
            /* Disable the plugin */
            MetadataHandler.PLUGIN.getServer().getPluginManager().disablePlugin(MetadataHandler.PLUGIN);
            return;
        }

        // Create a BukkitAudiences instance (adventure) in order to use the minecraft-extras help system
        bukkitAudiences = BukkitAudiences.create(MetadataHandler.PLUGIN);

        minecraftHelp = new MinecraftHelp<CommandSender>(
                "/freeminecraftmodels help",
                bukkitAudiences::sender,
                manager
        );

        // Override the default exception handlers
        new MinecraftExceptionHandler<CommandSender>()
                .withInvalidSyntaxHandler()
                .withInvalidSenderHandler()
                .withNoPermissionHandler()
                .withArgumentParsingHandler()
                .withCommandExecutionHandler()
                .withDecorator(
                        component -> text()
                                .append(text("[", NamedTextColor.DARK_GRAY))
                                .append(text("Example", NamedTextColor.GOLD))
                                .append(text("] ", NamedTextColor.DARK_GRAY))
                                .append(component).build()
                ).apply(manager, bukkitAudiences::sender);

        constructCommands();
    }

    public void constructCommands() {
        // Base command builder
        final Command.Builder<CommandSender> builder = manager.commandBuilder("freeminecraftmodels", "fmm");

        manager.command(builder.literal("help")
                .argument(StringArgument.optional("query", StringArgument.StringMode.GREEDY))
                .handler(context -> {
                    minecraftHelp.queryCommands(context.getOrDefault("query", ""), context.getSender());
                }));

        List<String> spawnTypes = List.of("static");
        List<String> entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));

        manager.command(builder.literal("spawn")
                .argument(StringArgument.<CommandSender>newBuilder("spawnType").withSuggestionsProvider(((objectCommandContext, s) -> spawnTypes)),
                        ArgumentDescription.of("Spawn Type"))
                .argument(StringArgument.<CommandSender>newBuilder("entityID").withSuggestionsProvider(((objectCommandContext, s) -> entityIDs)),
                        ArgumentDescription.of("Entity ID"))
                .meta(CommandMeta.DESCRIPTION, "Spawns a custom model of a specific type")
                .handler(context -> {
                    if (((String) context.get("spawnType")).equalsIgnoreCase("static"))
                       StaticEntity.create(context.get("entityID"),
                                ((Player)context.getSender()).rayTraceBlocks(300).getHitBlock().getLocation().add(0,1,0));
                }));

        manager.command(builder.literal("test")
                .handler(context -> {
                    Pig pig = (Pig) ((Player) context.getSender()).getWorld().spawnEntity(((Player) context.getSender()).getLocation(), EntityType.PIG);
                    Developer.warn("Pig bounding box: " + pig.getBoundingBox());
                    pig.getBoundingBox().expand(10D);
                    Developer.warn("Pig bounding box: " + pig.getBoundingBox());
                    pig.getBoundingBox().expand(10, 10, 10);
                    Developer.warn("Pig bounding box: " + pig.getBoundingBox());
                }));


        manager.command(builder.literal("reload")
                .handler(context -> {
                    MetadataHandler.PLUGIN.onDisable();
                    MetadataHandler.PLUGIN.onEnable();
                }));
    }

}
