package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlocksAtlasGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void materializesOnlyPackOwnedEntityTexturesAndPreservesOtherSources() throws Exception {
        Path atlas = temporaryDirectory.resolve("assets/minecraft/atlases/blocks.json");
        Files.createDirectories(atlas.getParent());
        Files.writeString(atlas, """
                {
                  "sources": [
                    {
                      "type": "minecraft:single",
                      "resource": "minecraft:block/stone"
                    },
                    {
                      "type": "directory",
                      "source": "entity",
                      "prefix": "entity/"
                    },
                    {
                      "type": "minecraft:directory",
                      "source": "block",
                      "prefix": "block/"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        writeTexture("assets/freeminecraftmodels/textures/entity/griffin/body.png");
        writeTexture("assets/freeminecraftmodels/textures/entity/griffin/eyes.glow.png");
        writeTexture("assets/freeminecraftmodels/textures/block/not_an_entity.png");
        writeTexture("assets/minecraft/textures/entity/fishing/fishing_hook.png");

        BlocksAtlasGenerator.materialize(temporaryDirectory);

        JsonElement actual = JsonParser.parseString(Files.readString(atlas, StandardCharsets.UTF_8));
        JsonElement expected = JsonParser.parseString("""
                {
                  "sources": [
                    {
                      "type": "minecraft:single",
                      "resource": "minecraft:block/stone"
                    },
                    {
                      "type": "minecraft:single",
                      "resource": "freeminecraftmodels:entity/griffin/body"
                    },
                    {
                      "type": "minecraft:single",
                      "resource": "freeminecraftmodels:entity/griffin/eyes.glow"
                    },
                    {
                      "type": "minecraft:directory",
                      "source": "block",
                      "prefix": "block/"
                    }
                  ]
                }
                """);
        assertEquals(expected, actual);
    }

    @Test
    void preservesExplicitSourcesAndDoesNotDuplicateTheirResources() throws Exception {
        Path atlas = temporaryDirectory.resolve("assets/minecraft/atlases/blocks.json");
        Files.createDirectories(atlas.getParent());
        Files.writeString(atlas, """
                {
                  "sources": [
                    {
                      "type": "minecraft:single",
                      "resource": "freeminecraftmodels:entity/griffin/body",
                      "sprite": "freeminecraftmodels:entity/griffin/body_alias"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        writeTexture("assets/freeminecraftmodels/textures/entity/griffin/body.png");

        BlocksAtlasGenerator.materialize(temporaryDirectory);
        BlocksAtlasGenerator.materialize(temporaryDirectory);

        JsonElement actual = JsonParser.parseString(Files.readString(atlas, StandardCharsets.UTF_8));
        JsonElement expected = JsonParser.parseString("""
                {
                  "sources": [
                    {
                      "type": "minecraft:single",
                      "resource": "freeminecraftmodels:entity/griffin/body",
                      "sprite": "freeminecraftmodels:entity/griffin/body_alias"
                    },
                    {
                      "type": "minecraft:single",
                      "resource": "freeminecraftmodels:entity/griffin/body"
                    }
                  ]
                }
                """);
        assertEquals(expected, actual);
    }

    private void writeTexture(String relativePath) throws Exception {
        Path texture = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(texture.getParent());
        Files.write(texture, new byte[]{0});
    }
}
