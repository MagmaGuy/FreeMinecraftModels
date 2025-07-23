package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public class ParsedTexture {
    @Getter
    private String filename;
    private double height;
    private double width;
    private double uvHeight;
    private double uvWidth;
    @Getter
    private double frameTime = 1;
    @Getter
    private Integer id;
    @Getter
    private boolean isValid = false;
    private String imagePath;

    public ParsedTexture(Map<?, ?> textureObject, String modelName, int imageIndex) {
        try {
            filename = StringToResourcePackFilename.convert((String) textureObject.get("name"));
            //So while there is an ID in blockbench it is not what it uses internally, what it uses internally is the ordered list of textures. Don't ask why.
            id = imageIndex;
            if (!filename.contains(".png")) {
                if (!filename.contains(".")) filename += ".png";
                else filename.split("\\.")[0] += ".png";
            }

            File imageFile = generateImageFile(textureObject, modelName);

            if (textureObject.get("height") != null) {
                this.height = (double) textureObject.get("height");
                this.width = (double) textureObject.get("width");
                this.uvHeight = (double) textureObject.get("uv_height");
                this.uvWidth = (double) textureObject.get("uv_width");
                this.frameTime = (double) textureObject.get("frame_time");
            } else {
                BufferedImage bufferedImage = ImageIO.read(imageFile);
                this.height = bufferedImage.getHeight();
                this.width = bufferedImage.getWidth();
                this.uvHeight = bufferedImage.getHeight();
                this.uvWidth = bufferedImage.getWidth();
            }

        } catch (Exception e) {
            Logger.warn("Failed to parse texture " + textureObject.get("name") + "!");
            Logger.warn("JSON: " + textureObject);
            e.printStackTrace();
            isValid = false;
            return;
        }

        if (isAnimated()) {
            generateMCMetaFile();
        }

        isValid = true;
    }

    private File generateImageFile(Map<?, ?> textureObject, String modelName) {
        String base64Image = (String) textureObject.get("source");
        base64Image = base64Image.split(",")[base64Image.split(",").length - 1];
        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64Image));
        imagePath = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                File.separatorChar + "output" +
                File.separatorChar + "FreeMinecraftModels" +
                File.separatorChar + "assets" +
                File.separatorChar + "freeminecraftmodels" +
                File.separatorChar + "textures" +
                File.separatorChar + "entity" +
                File.separatorChar + modelName +
                File.separatorChar + filename;
        File imageFile = new File(imagePath);
        try {
            FileUtils.writeByteArrayToFile(imageFile, inputStream.readAllBytes());
        } catch (IOException e) {
            Logger.warn("Failed to write image file " + imageFile.getAbsolutePath() + "!");
            throw new RuntimeException(e);
        }
        return imageFile;
    }

    private void generateMCMetaFile() {
        File mcMetaFile = new File(imagePath + ".mcmeta");
        AnimationMeta animationMeta = new AnimationMeta(frameTime);

        try {
            String json = new com.google.gson.GsonBuilder()
                    .create()
                    .toJson(animationMeta);
            FileUtils.writeStringToFile(mcMetaFile, json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to write .mcmeta file " + mcMetaFile.getAbsolutePath() + "!");
            throw new RuntimeException(e);
        }
    }


    public boolean isAnimated() {
        return height != uvHeight || width != uvWidth;
    }

    /**
     * Returns the uvHeight because height is the raw file height, and when using animated textures we use the uv height
     * for Minecraft and let it know that it has animation frames.
     *
     * @return The height to be used for resource pack generation
     */
    public double getTextureHeight() {
        return uvHeight;
    }

    /**
     * Returns the uvWidth because width is hte raw file width, and whne using animated textures we use the uv width
     * for Minecraft and let it know that it has animation frames.
     *
     * @return The width to be used for resource pack generation
     */
    public double getTextureWidth() {
        return uvWidth;
    }

    private static class AnimationMeta {
        private final Animation animation;

        public AnimationMeta(double frametime) {
            this.animation = new Animation(frametime);
        }

        private static class Animation {
            private final double frametime;

            public Animation(double frametime) {
                this.frametime = frametime;
            }
        }
    }
}
