package com.magmaguy.freeminecraftmodels;

import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.magmacore.MagmaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

public abstract class MockBukkitTestSupport {
    protected ServerMock server;
    protected PluginMock plugin;

    @BeforeEach
    protected void setUpMockBukkit() {
        server = MockBukkit.mock(new ServerMock() {
            @Override
            public String getBukkitVersion() {
                return "1.21.11-R0.1-SNAPSHOT";
            }

            @Override
            public String getMinecraftVersion() {
                return "1.21.11";
            }
        });
        plugin = MockBukkit.createMockPlugin("FreeMinecraftModels", "2.9.1");
        plugin.getDataFolder().mkdirs();
        MetadataHandler.PLUGIN = plugin;
        MagmaCore.createInstance(plugin);
        DisplayModelRegistry.shutdown();
    }

    @AfterEach
    protected void tearDownMockBukkit() {
        DisplayModelRegistry.shutdown();
        if (plugin != null) {
            MagmaCore.shutdown(plugin);
        }
        MetadataHandler.PLUGIN = null;
        MockBukkit.unmock();
    }
}
