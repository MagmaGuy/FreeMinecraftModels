package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.CommandManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeMinecraftModelsCommandMatrixTest {
    private ServerMock server;
    private MockFreeMinecraftModelsPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.loadWith(
                MockFreeMinecraftModelsPlugin.class,
                new ByteArrayInputStream("""
                        name: FreeMinecraftModels
                        version: 2.9.1-test
                        main: com.magmaguy.freeminecraftmodels.commands.MockFreeMinecraftModelsPlugin
                        api-version: '1.21'
                        commands:
                          freeminecraftmodels:
                            aliases: [fmm]
                            description: Main command
                        permissions:
                          freeminecraftmodels.*:
                            default: op
                          freeminecraftmodels.admin:
                            default: op
                          freeminecraftmodels.deleteall:
                            default: op
                          freeminecraftmodels.disguise.self:
                            default: op
                          freeminecraftmodels.disguise.others:
                            default: op
                          freeminecraftmodels.menu:
                            default: true
                          freeminecraftmodels.shop:
                            default: true
                        """.getBytes(StandardCharsets.UTF_8)));
        MetadataHandler.PLUGIN = plugin;
        MagmaCore.createInstance(plugin);
        registerPluginCommands();
    }

    @AfterEach
    void tearDown() {
        CommandManager.shutdown();
        MagmaCore.shutdown(plugin);
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }

    @Test
    void registersExpectedCommandAliases() {
        Set<String> aliases = CommandManager.getCommandManagers().stream()
                .flatMap(commandManager -> commandManager.commands.stream())
                .flatMap(command -> command.getAliases().stream())
                .collect(Collectors.toSet());

        assertTrue(aliases.containsAll(Set.of(
                "admin",
                "craftify",
                "debug",
                "deleteall",
                "disguise",
                "disguiselist",
                "giveitem",
                "hitbox",
                "itemify",
                "location",
                "mount",
                "packetdebug",
                "reload",
                "spawn",
                "stats",
                "undisguise",
                "version")));
    }

    @Test
    void pluginCommandAndAliasRouteToVersionCommand() {
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "freeminecraftmodels version"));
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "fmm version"));
    }

    @Test
    void safeCommandBodiesReturnDiagnosticsThroughCommandMap() {
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "fmm stats"));
        assertTrue(server.getConsoleSender().nextMessage().contains("Loaded model count"));
        assertTrue(server.getConsoleSender().nextMessage().contains("Loaded dynamic entities"));

        PlayerMock player = server.addPlayer("Cleaner");
        player.setOp(true);

        assertTrue(server.dispatchCommand(player, "fmm disguiselist"));
        assertTrue(player.nextMessage().contains("No players are currently disguised"));

        assertTrue(server.dispatchCommand(player, "fmm disguise missing_model"));
        assertTrue(player.nextMessage().contains("Invalid entity ID"));

        assertTrue(server.dispatchCommand(player, "fmm deleteall 0"));
        assertTrue(player.nextMessage().contains("Radius must be greater than 0"));

        DeleteAllCommand deleteAllCommand = new DeleteAllCommand();
        deleteAllCommand.execute(new CommandData(server.getConsoleSender(), new String[]{"deleteall", "10"}, deleteAllCommand));
        assertTrue(server.getConsoleSender().nextMessage().contains("Only players can use a delete radius"));
    }

    private void registerPluginCommands() {
        CommandManager manager = new CommandManager(plugin, "freeminecraftmodels");
        manager.registerCommand(new MountCommand());
        manager.registerCommand(new HitboxDebugCommand());
        manager.registerCommand(new BedrockDebugCommand());
        manager.registerCommand(new PacketDebugCommand());
        manager.registerCommand(new LocationDebugCommand());
        manager.registerCommand(new DeleteAllCommand());
        manager.registerCommand(new ReloadCommand());
        manager.registerCommand(new SpawnCommand());
        manager.registerCommand(new StatsCommand());
        manager.registerCommand(new VersionCommand());
        manager.registerCommand(new FreeMinecraftModelsCommand());
        manager.registerCommand(new DisguiseCommand());
        manager.registerCommand(new UndisguiseCommand());
        manager.registerCommand(new DisguiseListCommand());
        manager.registerCommand(new ItemifyCommand());
        manager.registerCommand(new CraftifyCommand());
        manager.registerCommand(new AdminCommand());
        manager.registerCommand(new GiveItemCommand());
    }
}
