package com.magmaguy.freeminecraftmodels.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.CubeBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
import com.magmaguy.freeminecraftmodels.utils.LoopType;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;
import com.magmaguy.magmacore.util.Logger;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BedrockEntityBundleExporter {
    public static final String PROPERTY_NAMESPACE = "freeminecraftmodels";
    public static final String BUNDLE_ROOT = "assets/freeminecraftmodels/rspm_bedrock_pack";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BedrockEntityBundleExporter() {
    }

    public static void export(FileModelConverter converter) {
        if (converter == null || converter.getSkeletonBlueprint() == null || converter.getID() == null) {
            return;
        }
        if (converter.getParsedTextures() == null || converter.getParsedTextures().isEmpty()) {
            Logger.warn("Skipping Bedrock custom entity export for " + converter.getID() + ": model has no textures.");
            return;
        }

        try {
            File root = new File(MetadataHandler.PLUGIN.getDataFolder(),
                    "output/FreeMinecraftModels/" + BUNDLE_ROOT);
            String modelId = converter.getID();
            List<BoneBlueprint> visualBones = visualBones(converter);
            BedrockTextureInfo textureInfo = writeTextures(root, modelId, converter.getParsedTextures());

            writeMaterials(root);
            writeGeometry(root, modelId, textureInfo, visualBones);
            writeRenderController(root, modelId);
            writeAnimations(root, modelId, converter, visualBones);
            writeAnimationControllers(root, modelId, converter, visualBones);
            writeEntity(root, modelId, textureInfo, converter);
        } catch (Exception exception) {
            Logger.warn("Failed to export Bedrock custom entity bundle for "
                    + converter.getID() + ": " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    public static List<String> sortedAnimationNames(FileModelConverter converter) {
        if (converter == null || converter.getAnimationsBlueprint() == null) {
            return List.of();
        }
        return converter.getAnimationsBlueprint().getAnimations().keySet().stream()
                .sorted()
                .toList();
    }

    public static List<String> exportedAnimationNames(FileModelConverter converter) {
        List<String> sortedAnimations = sortedAnimationNames(converter);
        if (sortedAnimations.isEmpty()) {
            return List.of("idle");
        }
        return sortedAnimations;
    }

    public static List<BoneBlueprint> visualBones(FileModelConverter converter) {
        if (converter == null || converter.getSkeletonBlueprint() == null) {
            return List.of();
        }
        Set<BoneBlueprint> ordered = new LinkedHashSet<>();
        for (BoneBlueprint root : converter.getSkeletonBlueprint().getMainModel()) {
            addVisualBone(root, ordered);
        }
        return new ArrayList<>(ordered);
    }

    private static void addVisualBone(BoneBlueprint bone, Set<BoneBlueprint> ordered) {
        if (bone == null) {
            return;
        }
        if (isVisualBone(bone)) {
            ordered.add(bone);
        }
        for (BoneBlueprint child : bone.getBoneBlueprintChildren()) {
            addVisualBone(child, ordered);
        }
    }

    private static boolean isVisualBone(BoneBlueprint bone) {
        return !bone.isMountPoint()
                && !bone.isNameTag()
                && !"hitbox".equalsIgnoreCase(bone.getOriginalBoneName());
    }

    public static String bonePropertyName(int index) {
        return PROPERTY_NAMESPACE + ":bone" + index;
    }

    public static String animationPropertyName(int index) {
        return PROPERTY_NAMESPACE + ":anim" + index;
    }

    public static String identifier(String modelId) {
        return PROPERTY_NAMESPACE + ":" + modelId;
    }

    public static String safeBoneName(BoneBlueprint bone) {
        return safeName(bone.getOriginalBoneName());
    }

    public static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "bone";
        }
        return StringToResourcePackFilename.convert(raw)
                .toLowerCase(Locale.ROOT)
                .replace(':', '_')
                .replace('/', '_')
                .replace('\\', '_');
    }

    private static void writeMaterials(File root) throws Exception {
        JsonObject materials = new JsonObject();
        JsonObject versioned = new JsonObject();
        JsonObject oneSided = new JsonObject();
        JsonArray defines = new JsonArray();
        defines.add("USE_OVERLAY");
        defines.add("USE_COLOR_MASK");
        oneSided.add("+defines", defines);
        versioned.add("entity_change_color_one_sided:entity", oneSided);

        JsonObject alpha = new JsonObject();
        JsonArray alphaDefines = new JsonArray();
        alphaDefines.add("ALPHA_TEST");
        alpha.add("+defines", alphaDefines);
        alpha.addProperty("msaaSupport", "Both");
        versioned.add("entity_alphatest_change_color_one_sided:entity_change_color_one_sided", alpha);

        materials.addProperty("version", "1.0.0");
        for (Map.Entry<String, JsonElement> entry : versioned.entrySet()) {
            materials.add(entry.getKey(), entry.getValue());
        }

        JsonObject rootJson = new JsonObject();
        rootJson.add("materials", materials);
        writeJson(new File(root, "materials/entity.material"), rootJson);
    }

    private static BedrockTextureInfo writeTextures(File root, String modelId, List<ParsedTexture> textures) throws Exception {
        File sourceDir = new File(MetadataHandler.PLUGIN.getDataFolder(),
                "output/FreeMinecraftModels/assets/freeminecraftmodels/textures/entity/" + modelId);
        File destinationDir = new File(root, "textures/entity/" + modelId);
        Files.createDirectories(destinationDir.toPath());

        if (textures.size() == 1) {
            ParsedTexture texture = textures.getFirst();
            File source = new File(sourceDir, texture.getFilename());
            if (source.isFile()) {
                Files.copy(source.toPath(), new File(destinationDir, texture.getFilename()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            List<TexturePlacement> placements = List.of(new TexturePlacement(
                    0,
                    0,
                    Math.max(1, (int) Math.round(texture.getTextureWidth())),
                    Math.max(1, (int) Math.round(texture.getTextureHeight()))));
            return new BedrockTextureInfo(
                    "textures/entity/" + modelId + "/" + texture.getFilename().replace(".png", ""),
                    placements.getFirst().width,
                    placements.getFirst().height,
                    placements);
        }

        List<BufferedImage> images = new ArrayList<>();
        List<TexturePlacement> placements = new ArrayList<>();
        int atlasWidth = 0;
        int atlasHeight = 1;
        for (ParsedTexture texture : textures) {
            File source = new File(sourceDir, texture.getFilename());
            BufferedImage image = source.isFile() ? ImageIO.read(source) : null;
            int textureWidth = Math.max(1, (int) Math.round(texture.getTextureWidth()));
            int textureHeight = Math.max(1, (int) Math.round(texture.getTextureHeight()));
            if (image == null) {
                image = new BufferedImage(textureWidth, textureHeight, BufferedImage.TYPE_INT_ARGB);
            }
            images.add(image);
            placements.add(new TexturePlacement(atlasWidth, 0, textureWidth, textureHeight));
            atlasWidth += image.getWidth();
            atlasHeight = Math.max(atlasHeight, image.getHeight());
        }
        atlasWidth = Math.max(1, atlasWidth);

        BufferedImage atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        try {
            int x = 0;
            for (BufferedImage image : images) {
                graphics.drawImage(image, x, 0, null);
                x += image.getWidth();
            }
        } finally {
            graphics.dispose();
        }

        String atlasFilename = "atlas.png";
        ImageIO.write(atlas, "png", new File(destinationDir, atlasFilename));
        return new BedrockTextureInfo(
                "textures/entity/" + modelId + "/" + atlasFilename.replace(".png", ""),
                atlasWidth,
                atlasHeight,
                placements);
    }

    private static void writeGeometry(File root, String modelId, BedrockTextureInfo textureInfo,
                                      List<BoneBlueprint> visualBones) throws Exception {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.freeminecraftmodels." + modelId);
        description.addProperty("texture_width", textureInfo.width);
        description.addProperty("texture_height", textureInfo.height);
        description.addProperty("visible_bounds_width", 8);
        description.addProperty("visible_bounds_height", 8);
        description.add("visible_bounds_offset", array(0, 2, 0));

        JsonArray bones = new JsonArray();
        for (BoneBlueprint bone : visualBones) {
            JsonObject bedrockBone = new JsonObject();
            bedrockBone.addProperty("name", safeBoneName(bone));
            if (bone.getParent() != null && visualBones.contains(bone.getParent())) {
                bedrockBone.addProperty("parent", safeBoneName(bone.getParent()));
            }
            Vector3f pivot = bone.getOriginalOrigin();
            if (pivot.lengthSquared() > 0.000001f) {
                bedrockBone.add("pivot", bedrockPosition(pivot.x, pivot.y, pivot.z));
            }
            Vector3f rotation = bone.getBlueprintOriginalBoneRotation();
            if (rotation.lengthSquared() > 0.000001f) {
                bedrockBone.add("rotation", bedrockRotation(
                        Math.toDegrees(rotation.x),
                        Math.toDegrees(rotation.y),
                        Math.toDegrees(rotation.z)));
            }
            JsonArray cubes = new JsonArray();
            for (CubeBlueprint cubeBlueprint : bone.getCubeBlueprintChildren()) {
                JsonObject cube = convertCube(bone, cubeBlueprint, textureInfo);
                if (cube != null) {
                    cubes.add(cube);
                }
            }
            if (!cubes.isEmpty()) {
                bedrockBone.add("cubes", cubes);
            }
            bones.add(bedrockBone);
        }

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", bones);

        JsonArray geometryArray = new JsonArray();
        geometryArray.add(geometry);

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("format_version", "1.21.0");
        rootJson.add("minecraft:geometry", geometryArray);
        writeJson(new File(root, "models/entity/freeminecraftmodels/" + modelId + ".geo.json"), rootJson);
    }

    private static JsonObject convertCube(BoneBlueprint bone, CubeBlueprint cubeBlueprint, BedrockTextureInfo textureInfo) {
        Map<String, Object> blockbenchCube = cubeBlueprint.getOriginalCubeJSON();
        Object fromObject = blockbenchCube.get("from");
        Object toObject = blockbenchCube.get("to");
        if (!(fromObject instanceof List<?> from) || !(toObject instanceof List<?> to) || from.size() < 3 || to.size() < 3) {
            return null;
        }

        double fx = number(from.get(0));
        double fy = number(from.get(1));
        double fz = number(from.get(2));
        double tx = number(to.get(0));
        double ty = number(to.get(1));
        double tz = number(to.get(2));

        double originX = Math.min(fx, tx);
        double originY = Math.min(fy, ty);
        double originZ = Math.min(fz, tz);
        double sizeX = Math.abs(tx - fx);
        double sizeY = Math.abs(ty - fy);
        double sizeZ = Math.abs(tz - fz);

        JsonObject cube = new JsonObject();
        cube.add("origin", array(-(originX + sizeX), originY, originZ));
        cube.add("size", array(sizeX, sizeY, sizeZ));
        addCubeInflate(cube, blockbenchCube);
        addCubeRotation(cube, blockbenchCube);

        Object facesObject = blockbenchCube.get("faces");
        if (facesObject instanceof Map<?, ?> faces) {
            JsonObject uv = new JsonObject();
            for (String face : List.of("north", "east", "south", "west", "up", "down")) {
                Object faceObject = faces.get(face);
                if (faceObject instanceof Map<?, ?> faceMap) {
                    JsonObject converted = convertFace(face, faceMap, textureInfo);
                    if (converted != null) {
                        uv.add(face, converted);
                    }
                }
            }
            if (!uv.entrySet().isEmpty()) {
                cube.add("uv", uv);
            }
        }
        return cube;
    }

    private static void addCubeInflate(JsonObject cube, Map<String, Object> blockbenchCube) {
        Object inflate = blockbenchCube.get("inflate");
        if (inflate instanceof Number number && Math.abs(number.doubleValue()) > 0.0001) {
            cube.addProperty("inflate", round(number.doubleValue()));
        }
    }

    private static void addCubeRotation(JsonObject cube, Map<String, Object> blockbenchCube) {
        Object rotationObject = blockbenchCube.get("rotation");
        if (!(rotationObject instanceof List<?> rotation) || rotation.size() < 3) {
            return;
        }

        double xRotation = number(rotation.get(0));
        double yRotation = number(rotation.get(1));
        double zRotation = number(rotation.get(2));
        if (Math.abs(xRotation) < 0.0001 && Math.abs(yRotation) < 0.0001 && Math.abs(zRotation) < 0.0001) {
            return;
        }

        cube.add("rotation", bedrockRotation(xRotation, yRotation, zRotation));

        Object originObject = blockbenchCube.get("origin");
        if (originObject instanceof List<?> origin && origin.size() >= 3) {
            cube.add("pivot", bedrockPosition(number(origin.get(0)), number(origin.get(1)), number(origin.get(2))));
        }
    }

    private static JsonObject convertFace(String faceName, Map<?, ?> face, BedrockTextureInfo textureInfo) {
        Object uvObject = face.get("uv");
        if (!(uvObject instanceof List<?> values) || values.size() < 4) {
            return null;
        }
        TexturePlacement placement = textureInfo.placement(textureIndex(face.get("texture")));
        double u1 = clamp(number(values.get(0)), 0.0, placement.width) + placement.x;
        double v1 = clamp(number(values.get(1)), 0.0, placement.height) + placement.y;
        double u2 = clamp(number(values.get(2)), 0.0, placement.width) + placement.x;
        double v2 = clamp(number(values.get(3)), 0.0, placement.height) + placement.y;

        double uvX = u1;
        double uvY = v1;
        double uvSizeX = u2 - u1;
        double uvSizeY = v2 - v1;
        if ("up".equals(faceName) || "down".equals(faceName)) {
            uvX += uvSizeX;
            uvY += uvSizeY;
            uvSizeX *= -1;
            uvSizeY *= -1;
        }

        JsonObject result = new JsonObject();
        result.add("uv", array(uvX, uvY));
        result.add("uv_size", array2(uvSizeX, uvSizeY));
        Object rotation = face.get("rotation");
        if (rotation instanceof Number number && number.intValue() != 0) {
            result.addProperty("uv_rotation", number.intValue());
        }
        return result;
    }

    private static int textureIndex(Object textureObject) {
        if (textureObject instanceof Number number) {
            return number.intValue();
        }
        if (textureObject instanceof String string) {
            String numeric = string.startsWith("#") ? string.substring(1) : string;
            try {
                return Integer.parseInt(numeric);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static void writeRenderController(File root, String modelId) throws Exception {
        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", "Geometry.default");

        JsonArray materials = new JsonArray();
        JsonObject material = new JsonObject();
        material.addProperty("*", "Material.default");
        materials.add(material);
        controller.add("materials", materials);

        JsonArray textures = new JsonArray();
        textures.add("Texture.default");
        controller.add("textures", textures);

        JsonObject controllers = new JsonObject();
        controllers.add("controller.render.fmm_" + modelId, controller);

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("format_version", "1.8.0");
        rootJson.add("render_controllers", controllers);
        writeJson(new File(root, "render_controllers/" + modelId + ".render_controllers.json"), rootJson);
    }

    private static void writeAnimations(File root, String modelId, FileModelConverter converter,
                                        List<BoneBlueprint> visualBones) throws Exception {
        JsonObject animations = new JsonObject();
        List<String> exportedAnimations = exportedAnimationNames(converter);
        if (converter.getAnimationsBlueprint() != null) {
            for (String animationName : exportedAnimations) {
                AnimationBlueprint animation = converter.getAnimationsBlueprint().getAnimations().get(animationName);
                if (animation == null) {
                    continue;
                }
                JsonObject animationJson = new JsonObject();
                animationJson.addProperty("animation_length", Math.max(0.05, animation.getDuration() / 20.0));
                writeLoopMode(animationJson, animation.getLoopType());
                animationJson.add("bones", bedrockAnimationBones(
                        animation, visualBones, converter.getBlockBenchVersion()));
                animations.add("animation.fmm." + modelId + "." + safeName(animationName), animationJson);
            }
        }
        if (animations.entrySet().isEmpty()) {
            animations.add("animation.fmm." + modelId + ".idle", noopAnimation(visualBones));
        }

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("format_version", "1.8.0");
        rootJson.add("animations", animations);
        writeJson(new File(root, "animations/" + modelId + ".animation.json"), rootJson);
    }

    private static void writeAnimationControllers(File root, String modelId, FileModelConverter converter,
                                                  List<BoneBlueprint> visualBones) throws Exception {
        List<String> animations = exportedAnimationNames(converter);
        if (animations.isEmpty()) {
            return;
        }

        JsonObject controllers = new JsonObject();
        for (int i = 0; i < animations.size(); i++) {
            String animation = animations.get(i);
            int propertyIndex = i / 24;
            int bit = 1 << (i % 24);
            String query = "math.mod(math.floor(query.property('" + animationPropertyName(propertyIndex)
                    + "') / " + bit + "), 2)";

            JsonObject playState = new JsonObject();
            JsonArray playAnimations = new JsonArray();
            playAnimations.add(safeName(animation));
            playState.add("animations", playAnimations);
            playState.addProperty("blend_transition", 0.1);
            JsonArray playTransitions = new JsonArray();
            JsonObject toStop = new JsonObject();
            toStop.addProperty("stop", query + " == 0");
            playTransitions.add(toStop);
            playState.add("transitions", playTransitions);

            JsonObject stopState = new JsonObject();
            stopState.addProperty("blend_transition", 0.1);
            JsonArray stopTransitions = new JsonArray();
            JsonObject toPlay = new JsonObject();
            toPlay.addProperty("play", query + " == 1");
            stopTransitions.add(toPlay);
            stopState.add("transitions", stopTransitions);

            JsonObject states = new JsonObject();
            states.add("play", playState);
            states.add("stop", stopState);

            JsonObject controller = new JsonObject();
            controller.addProperty("initial_state", "stop");
            controller.add("states", states);
            controllers.add("controller.animation.fmm." + modelId + "." + safeName(animation), controller);
        }

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("format_version", "1.10.0");
        rootJson.add("animation_controllers", controllers);
        writeJson(new File(root, "animation_controllers/" + modelId + ".animation_controllers.json"), rootJson);
    }

    private static void writeEntity(File root, String modelId, BedrockTextureInfo textureInfo,
                                    FileModelConverter converter) throws Exception {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier(modelId));

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest_change_color_one_sided");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", textureInfo.path);
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", "geometry.freeminecraftmodels." + modelId);
        description.add("geometry", geometry);

        List<String> exportedAnimations = exportedAnimationNames(converter);
        JsonObject animations = new JsonObject();
        for (String animation : exportedAnimations) {
            String safe = safeName(animation);
            animations.addProperty(safe, "animation.fmm." + modelId + "." + safe);
            animations.addProperty(safe + "_control", "controller.animation.fmm." + modelId + "." + safe);
        }
        if (!animations.entrySet().isEmpty()) {
            description.add("animations", animations);
        }

        JsonArray animate = new JsonArray();
        for (String animation : exportedAnimations) {
            animate.add(safeName(animation) + "_control");
        }
        if (!animate.isEmpty()) {
            JsonObject scripts = new JsonObject();
            scripts.add("animate", animate);
            description.add("scripts", scripts);
        }

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.fmm_" + modelId);
        description.add("render_controllers", renderControllers);

        JsonObject clientEntity = new JsonObject();
        clientEntity.add("description", description);

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("format_version", "1.10.0");
        rootJson.add("minecraft:client_entity", clientEntity);
        writeJson(new File(root, "entity/" + modelId + ".entity.json"), rootJson);
    }

    private static void writeJson(File file, JsonObject json) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }

    private static void writeLoopMode(JsonObject animationJson, LoopType loopType) {
        if (loopType == null) {
            animationJson.addProperty("loop", true);
            return;
        }
        switch (loopType) {
            case LOOP -> animationJson.addProperty("loop", true);
            case HOLD -> animationJson.addProperty("loop", "hold_on_last_frame");
            case ONCE -> animationJson.addProperty("loop", false);
        }
    }

    private static String defaultAnimation(List<String> animations) {
        if (animations.contains("spawn")) {
            return "spawn";
        }
        if (animations.contains("idle")) {
            return "idle";
        }
        return null;
    }

    private static JsonObject noopAnimation(List<BoneBlueprint> visualBones) {
        JsonObject animation = new JsonObject();
        animation.addProperty("animation_length", 0.05);
        animation.addProperty("loop", true);
        animation.add("bones", noopBones(visualBones));
        return animation;
    }

    private static JsonObject noopBones(List<BoneBlueprint> visualBones) {
        JsonObject bones = new JsonObject();
        String boneName = visualBones == null || visualBones.isEmpty()
                ? "freeminecraftmodels_autogenerated_root"
                : safeBoneName(visualBones.getFirst());
        JsonObject bone = new JsonObject();
        JsonObject rotation = new JsonObject();
        rotation.add("0", array(0, 0, 0));
        bone.add("rotation", rotation);
        bones.add(boneName, bone);
        return bones;
    }

    private static JsonObject bedrockAnimationBones(AnimationBlueprint animation, List<BoneBlueprint> visualBones,
                                                    int blockBenchVersion) {
        JsonObject bones = new JsonObject();
        animation.getAnimationFrames().entrySet().stream()
                .sorted(Comparator.comparing(entry -> safeBoneName(entry.getKey())))
                .forEach(entry -> {
                    AnimationFrame[] frames = entry.getValue();
                    if (frames == null || frames.length == 0) {
                        return;
                    }

                    JsonObject bone = new JsonObject();
                    JsonObject rotation = channel(frames, Channel.ROTATION, blockBenchVersion);
                    if (rotation != null) {
                        bone.add("rotation", rotation);
                    }
                    JsonObject position = channel(frames, Channel.POSITION, blockBenchVersion);
                    if (position != null) {
                        bone.add("position", position);
                    }
                    JsonObject scale = channel(frames, Channel.SCALE, blockBenchVersion);
                    if (scale != null) {
                        bone.add("scale", scale);
                    }
                    if (!bone.entrySet().isEmpty()) {
                        bones.add(safeBoneName(entry.getKey()), bone);
                    }
                });
        if (bones.entrySet().isEmpty()) {
            return noopBones(visualBones);
        }
        return bones;
    }

    private enum Channel {
        ROTATION,
        POSITION,
        SCALE
    }

    private static JsonObject channel(AnimationFrame[] frames, Channel channel, int blockBenchVersion) {
        if (allDefault(frames, channel, blockBenchVersion)) {
            return null;
        }

        JsonObject keyframes = new JsonObject();
        JsonArray previous = null;
        for (int tick = 0; tick < frames.length; tick++) {
            JsonArray current = vector(frames[tick], channel, blockBenchVersion);
            boolean last = tick == frames.length - 1;
            if (tick == 0 || last || !sameVector(previous, current)) {
                keyframes.add(timeKey(tick), current);
                previous = current;
            }
        }
        return keyframes;
    }

    private static boolean allDefault(AnimationFrame[] frames, Channel channel, int blockBenchVersion) {
        for (AnimationFrame frame : frames) {
            JsonArray vector = vector(frame, channel, blockBenchVersion);
            if (!sameVector(vector, defaultVector(channel))) {
                return false;
            }
        }
        return true;
    }

    private static JsonArray vector(AnimationFrame frame, Channel channel, int blockBenchVersion) {
        return switch (channel) {
            case ROTATION -> array(
                    Math.toDegrees(-frame.xRotation),
                    Math.toDegrees(-frame.yRotation),
                    Math.toDegrees(frame.zRotation));
            case POSITION -> array(
                    -frame.xPosition * 16.0,
                    frame.yPosition * 16.0,
                    frame.zPosition * 16.0);
            case SCALE -> array(
                    frame.scaleX == null ? 1.0 : frame.scaleX,
                    frame.scaleY == null ? 1.0 : frame.scaleY,
                    frame.scaleZ == null ? 1.0 : frame.scaleZ);
        };
    }

    private static JsonArray defaultVector(Channel channel) {
        return channel == Channel.SCALE ? array(1, 1, 1) : array(0, 0, 0);
    }

    private static boolean sameVector(JsonArray left, JsonArray right) {
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (Math.abs(left.get(i).getAsDouble() - right.get(i).getAsDouble()) > 0.0001) {
                return false;
            }
        }
        return true;
    }

    private static String timeKey(int tick) {
        double seconds = tick / 20.0;
        String text = String.format(Locale.ROOT, "%.4f", seconds);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static JsonArray array(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(round(x));
        array.add(round(y));
        array.add(round(z));
        return array;
    }

    private static JsonArray bedrockPosition(double x, double y, double z) {
        return array(-x, y, z);
    }

    private static JsonArray bedrockRotation(double x, double y, double z) {
        return array(-x, -y, z);
    }

    private static JsonArray array(double x, double y) {
        return array2(x, y);
    }

    private static JsonArray array2(double x, double y) {
        JsonArray array = new JsonArray();
        array.add(round(x));
        array.add(round(y));
        return array;
    }

    private static double number(Object object) {
        return object instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class BedrockTextureInfo {
        private final String path;
        private final int width;
        private final int height;
        private final List<TexturePlacement> placements;

        private BedrockTextureInfo(String path, int width, int height, List<TexturePlacement> placements) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.placements = placements;
        }

        private TexturePlacement placement(int index) {
            if (index >= 0 && index < placements.size()) {
                return placements.get(index);
            }
            return placements.getFirst();
        }
    }

    private static final class TexturePlacement {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private TexturePlacement(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
