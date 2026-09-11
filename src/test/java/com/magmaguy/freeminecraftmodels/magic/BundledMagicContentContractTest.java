package com.magmaguy.freeminecraftmodels.magic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledMagicContentContractTest {

    @Test
    void staffUsesTheCanonicalBundledModelAndBothWeaponsAreScriptFreeDefaults() throws IOException {
        BundledMagicContent.AssetSet staff = BundledMagicContent.staffAssets();
        String staffPrefix = "/" + staff.resourceDirectory() + "/" + staff.modelId();
        assertResource(staffPrefix + ".bbmodel");
        assertResource(staffPrefix + ".json");

        for (BundledMagicContent.AssetSet asset : BundledMagicContent.assetSets()) {
            String yml = readResource("/" + asset.resourceDirectory() + "/" + asset.modelId() + ".yml");
            assertTrue(yml.contains("scripts: []"), asset.modelId());
            assertTrue(yml.contains("FreeMinecraftModels owns this weapon's attacks."), asset.modelId());
            assertFalse(yml.contains("EliteMobs creates"), asset.modelId());
        }

        String wandYml = readResource("/" + BundledMagicContent.RESOURCE_DIRECTORY + "/"
                + BuiltInMagicWeapons.DEFAULT_WAND_ID + ".yml");
        assertTrue(wandYml.contains("material: BLAZE_ROD"));
        assertTrue(wandYml.contains("vanilla blaze rod"));
    }

    private static void assertResource(String path) throws IOException {
        try (InputStream stream = BundledMagicContentContractTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            assertTrue(stream.readAllBytes().length > 0, path);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = BundledMagicContentContractTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
