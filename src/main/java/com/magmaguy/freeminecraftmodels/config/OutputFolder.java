package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class OutputFolder {
    public static void initializeConfig() {
        ConfigurationEngine.directoryCreator("output");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases");
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.mcmeta", true);
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.png", true);
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases/blocks.json", true);
        try {
            zipDirectory(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels",
                    MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip");
        } catch (Exception exception) {
            Developer.warn("Failed to zip resource pack!");
            exception.printStackTrace();
        }
    }

    public static void zipDirectory(String sourceDirPath, String zipFilePath) throws IOException {
        if (new File(zipFilePath).exists()) {
            new File(zipFilePath).delete();
        }

        FileOutputStream fos = new FileOutputStream(zipFilePath);
        ZipOutputStream zipOut = new ZipOutputStream(fos);

        File sourceDir = new File(sourceDirPath);
        zipDir(sourceDir, null, zipOut);

        zipOut.close();
        fos.close();
    }

    private static void zipDir(File dir, String baseName, ZipOutputStream zipOut) throws IOException {
        File[] files = dir.listFiles();
        byte[] buffer = new byte[1024];

        for (File file : files) {
            String newBaseName;
            if (baseName == null)
                newBaseName = file.getName();
            else
                newBaseName = baseName + File.separatorChar + file.getName();
            if (file.isDirectory()) {
                zipDir(file, newBaseName, zipOut);
            } else {
                FileInputStream fis = new FileInputStream(file);
                zipOut.putNextEntry(new ZipEntry(newBaseName));

                int length;
                while ((length = fis.read(buffer)) > 0) {
                    zipOut.write(buffer, 0, length);
                }

                zipOut.closeEntry();
                fis.close();
            }
        }
    }
}
