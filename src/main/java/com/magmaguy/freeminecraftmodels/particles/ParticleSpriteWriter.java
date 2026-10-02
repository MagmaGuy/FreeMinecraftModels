package com.magmaguy.freeminecraftmodels.particles;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one particle effect's sprites into the resource pack: its texture under
 * {@code textures/entity/fmm_particles} (FMM's atlas generator registers it), one flat quad model
 * per sprite and a tintable item definition for each. Maps are ordered so the bytes are stable
 * across restarts and the pack hash does not change needlessly.
 */
public final class ParticleSpriteWriter {
    private static final Gson GSON = new Gson();

    private ParticleSpriteWriter() {
    }

    /**
     * @param assetsRoot the {@code assets/freeminecraftmodels} folder of the output pack
     * @param texture    the effect's PNG, or null to write a plain white texture
     */
    public static void write(File assetsRoot, ParticleSprites sprites, File texture) throws IOException {
        File textureFile = new File(assetsRoot, "textures/entity/fmm_particles/" + sprites.key() + ".png");
        Files.createDirectories(textureFile.getParentFile().toPath());
        if (texture != null) {
            Files.copy(texture.toPath(), textureFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            BufferedImage white = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) white.setRGB(x, y, 0xFFFFFFFF);
            ImageIO.write(white, "png", textureFile);
        }

        String textureReference = "freeminecraftmodels:entity/fmm_particles/" + sprites.key();
        for (int index = 0; index < sprites.spriteCount(); index++) {
            double[] uv = sprites.uv(index);
            writeJson(new File(assetsRoot, "models/particle/" + sprites.key() + "/" + index + ".json"),
                    model(textureReference, uv));
            writeJson(new File(assetsRoot, "items/particle/" + sprites.key() + "/" + index + ".json"),
                    itemDefinition("freeminecraftmodels:particle/" + sprites.key() + "/" + index));
        }
    }

    private static Map<String, Object> model(String texture, double[] uv) {
        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("0", texture);
        textures.put("particle", texture);

        // Both sides of the quad, each mirrored so the sprite reads the same way from either side.
        Map<String, Object> faces = new LinkedHashMap<>();
        faces.put("north", face(new double[]{uv[2], uv[1], uv[0], uv[3]}));
        faces.put("south", face(uv));

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("from", List.of(0, 0, 8));
        element.put("to", List.of(16, 16, 8));
        element.put("shade", false);
        element.put("faces", faces);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("textures", textures);
        model.put("elements", List.of(element));
        return model;
    }

    private static Map<String, Object> face(double[] uv) {
        Map<String, Object> face = new LinkedHashMap<>();
        face.put("uv", List.of(uv[0], uv[1], uv[2], uv[3]));
        face.put("texture", "#0");
        face.put("tintindex", 0);
        return face;
    }

    private static Map<String, Object> itemDefinition(String model) {
        Map<String, Object> tint = new LinkedHashMap<>();
        tint.put("type", "minecraft:custom_model_data");
        tint.put("index", 0);
        tint.put("default", 0xFFFFFF);

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("tints", List.of(tint));
        contents.put("type", "minecraft:model");
        contents.put("model", model);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("model", contents);
        return definition;
    }

    private static void writeJson(File file, Object json) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
