package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ImportsFolder {
    private static final String modelsDirectory = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "models";

    public static void initializeConfig() {
        File file = ConfigurationEngine.directoryCreator("imports");

        if (!file.exists()) {
            Developer.warn("Failed to create imports directory!");
            return;
        }

        if (!file.isDirectory()) {
            Developer.warn("Directory imports was not a directory!");
            return;
        }

        //First pass scans for zipped files
        for (File childFile : file.listFiles()) {
            try {
                if (childFile.getName().contains(".zip")) unzip(childFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //Second pass reads everything
        for (File childFile : file.listFiles()) {
            if (childFile.isDirectory()) processFolder(childFile);
            else processFile(childFile);
        }

        //Third pass - bbmodel files are not deleted, but fmmodel files are moved completely, meaning that some directories might be empty now
        for (File childFile : file.listFiles()) {
            if (childFile.isDirectory() && isEmptyDirectory(childFile)) deleteDirectories(childFile);
        }
    }

    private static boolean isEmptyDirectory(File directory) {
        if (directory.isFile()) return false;
        if (directory.isDirectory()) for (File child : directory.listFiles())
            if (!isEmptyDirectory(child)) return false;
        return true;
    }

    private static void deleteDirectories(File directory) {
        for (File child : directory.listFiles())
            deleteDirectories(child);
        directory.delete();
    }

    public static File unzip(File zippedFile) throws IOException {
        String mainDirectory = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "imports" + File.separatorChar;
        String fileZip = mainDirectory + zippedFile.getName();
        File finalDirectory = new File(mainDirectory + zippedFile.getName().replace(".zip", ""));
        byte[] buffer = new byte[1024];
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(fileZip));
        ZipEntry zipEntry = zipInputStream.getNextEntry();
        while (zipEntry != null) {
            File newFile = newFile(finalDirectory, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                // fix for Windows-created archives
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                // write file content
                FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                int len;
                while ((len = zipInputStream.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, len);
                }
                fileOutputStream.close();
            }
            zipEntry = zipInputStream.getNextEntry();
        }
        zipInputStream.closeEntry();
        zipInputStream.close();
        zippedFile.delete();
        return finalDirectory;
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separatorChar)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    private static void processFolder(File folder) {
        for (File childFile : folder.listFiles()) {
            if (childFile.getName().equals("desktop.ini")) continue; //more common than you'd think
            if (childFile.isDirectory()) processFolder(childFile);
            else processFile(childFile);
        }
        //folder.delete();  not sure I want to do this
    }

    private static void processFile(File childFile) {
        //First off: Is it a bbmodel file?
        if (childFile.getName().contains(".bbmodel")) {
            //Reading time
            Gson readerGson = new Gson();

            Reader reader;
            // create a reader
            try {
                reader = Files.newBufferedReader(Paths.get(childFile.getPath()));
            } catch (Exception ex) {
                Developer.warn("Failed to read file " + childFile.getAbsolutePath());
                return;
            }

            // convert JSON file to map
            Map<?, ?> jsonMap = readerGson.fromJson(reader, Map.class);

            //Writing time
            Gson writerGson = new Gson();
            //The objective here is to get every map that is actually used, and avoid every map that is not.
            HashMap<String, Object> minifiedMap = new HashMap<>();

            minifiedMap.put("resolution", jsonMap.get("resolution"));
            minifiedMap.put("elements", jsonMap.get("elements"));
            minifiedMap.put("outliner", jsonMap.get("outliner"));
            ArrayList<Map> minifiedTextures = new ArrayList<>();
            ((ArrayList) jsonMap.get("textures")).forEach(innerMap -> minifiedTextures.add(Map.of("source", ((LinkedTreeMap) innerMap).get("source"), "id", ((LinkedTreeMap) innerMap).get("id"), "name", ((LinkedTreeMap) innerMap).get("name"))));
            minifiedMap.put("textures", minifiedTextures);
            minifiedMap.put("animations", jsonMap.get("animations"));

            List<String> parentFiles = new ArrayList<>();
            File currentFile = childFile;
            while (true) {
                File parentFile = currentFile.getParentFile();
                if (parentFile.getName().equals("imports")) break;
                parentFiles.add(parentFile.getName());
                currentFile = parentFile;
            }
            String pathName;
            if (parentFiles.isEmpty())
                pathName = modelsDirectory + File.separatorChar + childFile.getName().replace(".bbmodel", ".fmmodel");
            else {
                pathName = modelsDirectory;
                for (int i = parentFiles.size() - 1; i > -1; i--)
                    pathName += File.separatorChar + parentFiles.get(i);
                pathName += File.separatorChar + childFile.getName().replace(".bbmodel", ".fmmodel");
            }

            try {
                FileUtils.writeStringToFile(new File(pathName), writerGson.toJson(minifiedMap), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Developer.warn("Failed to generate the minified file!");
                throw new RuntimeException(e);
            }
        } else if (childFile.getName().contains(".fmmodel")) {
            // Move each file to the destination directory
            try {
                Files.move(childFile.getAbsoluteFile().toPath(), Paths.get(modelsDirectory, childFile.getName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
            Developer.info("Moved: " + childFile.getName());
        } else {
            Developer.warn("Invalid file format detected in imports: " + childFile.getName());
        }
    }
}
