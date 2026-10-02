package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
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
    }

    @Test
    void everyInstalledFileShipsAndTheWandBringsItsParticles() throws IOException {
        for (BundledMagicContent.AssetSet asset : BundledMagicContent.assetSets())
            for (String resource : asset.resources()) assertResource("/" + resource);

        BundledMagicContent.AssetSet wand = BundledMagicContent.wandAssets();
        assertTrue(wand.modelAssets(), "The wand ships Dali's model and display JSON like the staff");
        String particle = readResource("/" + BundledMagicContent.RESOURCE_DIRECTORY
                + "/fmm_default_arcane_wand_particles.particle.json");
        ParticleEffectBlueprint effect = SnowstormParser.parse(particle, "fmm_default_arcane_wand_particles.particle.json");
        // The texture path's last segment names the PNG that ships beside the particle file.
        assertTrue(effect.texturePath().endsWith("/fmm_default_arcane_wand_particles"), effect.texturePath());
        assertTrue(wand.extraFiles().contains("fmm_default_arcane_wand_particles.png"));
        assertTrue(readResource("/" + BundledMagicContent.RESOURCE_DIRECTORY + "/" + wand.modelId() + ".bbmodel")
                .contains("\"file\":\"fmm_default_arcane_wand_particles.particle.json\""));
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
