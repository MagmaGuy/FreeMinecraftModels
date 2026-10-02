package com.magmaguy.freeminecraftmodels.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ItemBaseDeclarationTest {
    @TempDir
    Path directory;

    @Test
    void declaresTheYamlMaterialAsTheBedrockBaseItem() throws Exception {
        Path model = directory.resolve("models/fmm_default_arcane_wand.bbmodel");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{}");
        Files.writeString(directory.resolve("models/fmm_default_arcane_wand.yml"), "material: BLAZE_ROD\n");
        Path assets = directory.resolve("assets/freeminecraftmodels");

        ItemBaseDeclaration.write(assets.toFile(), "fmm_default_arcane_wand", model.toFile(), "BLAZE_ROD"::equals);

        assertEquals("{\"base_items\": [\"minecraft:blaze_rod\"]}\n",
                Files.readString(assets.resolve("rspm_item_bases/display/fmm_default_arcane_wand.json")));
    }

    @Test
    void writesNothingWithoutAKnownItemMaterial() throws Exception {
        Path model = directory.resolve("models/statue.bbmodel");
        Files.createDirectories(model.getParent());
        Files.writeString(directory.resolve("models/statue.yml"), "isEnabled: true\n");
        Files.writeString(directory.resolve("models/relic.yml"), "material: NOT_A_MATERIAL\n");
        Path assets = directory.resolve("assets/freeminecraftmodels");

        ItemBaseDeclaration.write(assets.toFile(), "statue", model.toFile(), "BLAZE_ROD"::equals);
        ItemBaseDeclaration.write(assets.toFile(), "relic", directory.resolve("models/relic.bbmodel").toFile(), "BLAZE_ROD"::equals);

        assertFalse(Files.exists(assets.resolve("rspm_item_bases")));
    }
}
