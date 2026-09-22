package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class OutputFolder {
    private OutputFolder() {
    }

    public static void initializeConfig() {
        String path = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath();
        String baseDirectory = path + File.separatorChar + "output";
        File mainFolder = new File(baseDirectory);
        try {
            Files.createDirectories(mainFolder.toPath());
            try (var generated = Files.list(mainFolder.toPath())) {
                for (var file : generated.toList()) {
                    if (!file.getFileName().toString().equals("FreeMinecraftModels.zip"))
                        FileUtils.forceDelete(file.toFile());
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to prepare output folder " + mainFolder, failure);
        }
        generateDirectory(mainFolder.getAbsolutePath());
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures");
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models");
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases");
        generateFileFromResources("pack.mcmeta", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.mcmeta");
        generateFileFromResources("pack.png", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.png");
        generateFileFromResources("blocks.json", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases" + File.separatorChar + "blocks.json");
    }

    public static void zipResourcePack() {
        File resourcePackFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath()
                + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels");
        try {
            BlocksAtlasGenerator.materialize(resourcePackFolder.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate the FreeMinecraftModels blocks atlas", e);
        }
        java.nio.file.Path published = resourcePackFolder.toPath().resolveSibling("FreeMinecraftModels.zip");
        java.nio.file.Path pending = published.resolveSibling("FreeMinecraftModels.zip.pending");
        try {
            if (!ZipFile.zip(resourcePackFolder, pending.toString()))
                throw new IOException("Resource pack archive could not be written");
            Files.move(pending, published, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to publish the FreeMinecraftModels resource pack", failure);
        } finally {
            try { Files.deleteIfExists(pending); }
            catch (IOException failure) { Logger.warn("Failed to remove incomplete resource pack " + pending); }
        }
    }

    private static void generateFileFromResources(String filename, String destination) {
        File newFile = new File(destination);
        try (InputStream inputStream = MetadataHandler.PLUGIN.getResource(filename)) {
            if (inputStream == null) {
                throw new IOException("Missing resource " + filename);
            }
            Files.createDirectories(newFile.toPath().getParent());
            Files.copy(inputStream, newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate resource pack element " + newFile, e);
        }
    }

    private static void generateDirectory(String path) {
        try {
            Files.createDirectories(new File(path).toPath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate directory " + path, e);
        }
    }
}
