package com.magmaguy.freeminecraftmodels.particles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.SnowstormParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ParticleSpritesTest {
    @TempDir
    Path directory;

    private ResolvedParticleEffect effect(String billboard, File texture) {
        return new ResolvedParticleEffect("spark", SnowstormParser.parse("""
                {"particle_effect": {"description": {"basic_render_parameters": {"texture": "textures/particle/spark"}},
                  "components": {"minecraft:particle_appearance_billboard": %s}}}""".formatted(billboard), "spark.json"),
                directory.resolve("spark.json").toFile(), texture);
    }

    private ResolvedParticleEffect dalisWand(File texture) throws Exception {
        String json = new String(getClass().getResourceAsStream("/particles/particle_wand.json").readAllBytes(),
                StandardCharsets.UTF_8);
        return new ResolvedParticleEffect("particle_wand", SnowstormParser.parse(json, "particle_wand.json"),
                directory.resolve("particle_wand.json").toFile(), texture);
    }

    private File texture(int width, int height) throws Exception {
        File file = directory.resolve("spark.png").toFile();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file);
        return file;
    }

    @Test
    void constantFlipbookHasOneSpritePerFrame() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("Wand Model", effect("""
                {"size": [0.1, 0.1], "uv": {"texture_width": 64, "texture_height": 8,
                  "flipbook": {"base_UV": [0, 0], "size_UV": [8, 8], "step_UV": [8, 0], "max_frame": 4}}}""",
                texture(64, 8)));
        assertEquals("wand_model/spark", sprites.key());
        assertEquals(4, sprites.spriteCount());
        assertArrayEquals(new double[]{4, 0, 6, 16}, sprites.uv(2), 1e-9);
        assertEquals(3, sprites.spriteIndex(0, 0, 3));
        assertEquals("freeminecraftmodels:particle/wand_model/spark/3", sprites.itemModel(3));
    }

    @Test
    void randomVariantsAreSampledIntoEverySprite() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", dalisWand(texture(16, 128)));
        assertEquals(32, sprites.spriteCount(), "Two by two random variants times eight frames");
        int index = sprites.spriteIndex(8, 8, 7);
        assertArrayEquals(new double[]{8, 15, 16, 16}, sprites.uv(index), 1e-9);
        assertEquals(sprites.spriteIndex(8, 8, 7), sprites.spriteIndex(8.2, 7.9, 7), "Nearest variant wins");
    }

    @Test
    void missingTextureCollapsesToOneWhiteSprite() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", dalisWand(null));
        assertTrue(sprites.whiteFallback());
        assertEquals(1, sprites.spriteCount());
        assertEquals(0, sprites.spriteIndex(8, 8, 5));
        assertArrayEquals(new double[]{0, 0, 16, 16}, sprites.uv(0), 1e-9);
    }

    @Test
    void noUvMeansTheWholeTexture() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", effect("{\"size\": [0.1, 0.1]}", texture(32, 32)));
        assertEquals(1, sprites.spriteCount());
        assertArrayEquals(new double[]{0, 0, 16, 16}, sprites.uv(0), 1e-9);
    }

    @Test
    void writesTextureModelsAndItemDefinitions() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", effect("""
                {"size": [0.1, 0.1], "uv": {"texture_width": 16, "texture_height": 16,
                  "flipbook": {"base_UV": [0, 0], "size_UV": [8, 8], "step_UV": [8, 0], "max_frame": 2}}}""",
                texture(16, 16)));
        Path assets = directory.resolve("assets/freeminecraftmodels");
        ParticleSpriteWriter.write(assets.toFile(), sprites, sprites.texture());

        assertTrue(Files.isRegularFile(assets.resolve("textures/entity/fmm_particles/wand/spark.png")));
        JsonObject model = JsonParser.parseString(Files.readString(assets.resolve("models/particle/wand/spark/1.json")))
                .getAsJsonObject();
        assertEquals("freeminecraftmodels:entity/fmm_particles/wand/spark",
                model.getAsJsonObject("textures").get("0").getAsString());
        JsonObject south = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("faces").getAsJsonObject("south");
        assertEquals("[8,0,16,8]", south.get("uv").toString().replace(".0", ""));
        assertEquals(0, south.get("tintindex").getAsInt());

        JsonObject item = JsonParser.parseString(Files.readString(assets.resolve("items/particle/wand/spark/1.json")))
                .getAsJsonObject().getAsJsonObject("model");
        assertEquals("freeminecraftmodels:particle/wand/spark/1", item.get("model").getAsString());
        assertEquals("minecraft:custom_model_data", item.getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void declaresTheSpriteItemsJavaOnlyForResourcePackManager() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", effect("{\"size\": [0.1, 0.1]}", texture(16, 16)));
        Path assets = directory.resolve("assets/freeminecraftmodels");
        ParticleSpriteWriter.write(assets.toFile(), sprites, sprites.texture());

        // Covers items/particle/wand/spark/<n>.json: Bedrock plays the effect natively, so the
        // sprites must not become Geyser custom items.
        Path declaration = assets.resolve("rspm_item_java_only/particle/wand/spark.json");
        assertTrue(Files.isRegularFile(declaration));
        assertTrue(JsonParser.parseString(Files.readString(declaration)).isJsonObject());
    }

    @Test
    void whiteFallbackWritesAPlainTexture() throws Exception {
        ParticleSprites sprites = ParticleSprites.of("wand", dalisWand(null));
        Path assets = directory.resolve("assets/freeminecraftmodels");
        ParticleSpriteWriter.write(assets.toFile(), sprites, null);
        BufferedImage white = ImageIO.read(assets.resolve("textures/entity/fmm_particles/wand/particle_wand.png").toFile());
        assertEquals(0xFFFFFFFF, white.getRGB(3, 3));
    }
}
