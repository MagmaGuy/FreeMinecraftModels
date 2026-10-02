package com.magmaguy.freeminecraftmodels.particles.snowstorm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangExpression;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangParser;
import com.magmaguy.freeminecraftmodels.particles.snowstorm.ParticleEffectBlueprint.*;

import java.util.*;

/**
 * Reads Bedrock/Snowstorm particle files. A component that is absent uses the default
 * Blockbench's preview (Wintersky) shows; a field missing inside a present component uses
 * Bedrock's field default. Components FMM cannot render on Java are listed, not rejected.
 */
public final class SnowstormParser {
    private static final Set<String> SUPPORTED = Set.of(
            "minecraft:emitter_initialization", "minecraft:emitter_local_space",
            "minecraft:emitter_rate_instant", "minecraft:emitter_rate_steady",
            "minecraft:emitter_lifetime_looping", "minecraft:emitter_lifetime_once",
            "minecraft:emitter_lifetime_expression",
            "minecraft:emitter_shape_point", "minecraft:emitter_shape_sphere", "minecraft:emitter_shape_box",
            "minecraft:emitter_shape_disc", "minecraft:emitter_shape_custom",
            "minecraft:particle_initialization", "minecraft:particle_initial_speed",
            "minecraft:particle_initial_spin", "minecraft:particle_motion_dynamic",
            "minecraft:particle_lifetime_expression", "minecraft:particle_appearance_billboard",
            "minecraft:particle_appearance_tinting", "minecraft:particle_appearance_lighting");

    private SnowstormParser() {
    }

    public static ParticleEffectBlueprint parse(String json, String fileName) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(fileName + " is not valid JSON: " + failure.getMessage(), failure);
        }
        if (!root.has("particle_effect") || !root.get("particle_effect").isJsonObject())
            throw new IllegalArgumentException(fileName + " has no particle_effect object");
        JsonObject effect = root.getAsJsonObject("particle_effect");
        JsonObject description = object(effect, "description");
        JsonObject render = object(description, "basic_render_parameters");
        JsonObject components = object(effect, "components");

        List<String> unsupported = new ArrayList<>();
        for (String name : components.keySet()) if (!SUPPORTED.contains(name)) unsupported.add(name);

        Emitter emitter = emitter(components, unsupported);
        Particle particle = particle(components, unsupported);
        return new ParticleEffectBlueprint(
                string(description, "identifier", ""),
                string(render, "texture", ""),
                string(render, "material", "particles_alpha"),
                curves(object(effect, "curves")),
                emitter, particle, List.copyOf(unsupported));
    }

    private static Emitter emitter(JsonObject components, List<String> unsupported) {
        JsonObject initialization = object(components, "minecraft:emitter_initialization");
        JsonObject localSpace = object(components, "minecraft:emitter_local_space");

        Rate rate;
        if (components.has("minecraft:emitter_rate_instant")) {
            rate = new Rate.Instant(expression(object(components, "minecraft:emitter_rate_instant"), "num_particles", 10));
        } else if (components.has("minecraft:emitter_rate_steady")) {
            JsonObject steady = object(components, "minecraft:emitter_rate_steady");
            rate = new Rate.Steady(expression(steady, "spawn_rate", 1), expression(steady, "max_particles", 50));
        } else if (components.has("minecraft:emitter_rate_manual")) {
            rate = new Rate.None();
        } else {
            rate = new Rate.Steady(MolangExpression.constant(4), MolangExpression.constant(100));
        }

        Lifetime lifetime;
        if (components.has("minecraft:emitter_lifetime_once")) {
            lifetime = new Lifetime.Once(expression(object(components, "minecraft:emitter_lifetime_once"), "active_time", 10));
        } else if (components.has("minecraft:emitter_lifetime_expression")) {
            JsonObject expression = object(components, "minecraft:emitter_lifetime_expression");
            lifetime = new Lifetime.Expression(expression(expression, "activation_expression", 1),
                    expression(expression, "expiration_expression", 0));
        } else if (components.has("minecraft:emitter_lifetime_looping")) {
            JsonObject looping = object(components, "minecraft:emitter_lifetime_looping");
            lifetime = new Lifetime.Looping(expression(looping, "active_time", 10), expression(looping, "sleep_time", 0));
        } else {
            lifetime = new Lifetime.Looping(MolangExpression.constant(1), MolangExpression.ZERO);
        }

        return new Emitter(
                expression(initialization, "creation_expression", 0),
                expression(initialization, "per_update_expression", 0),
                bool(localSpace, "position"), bool(localSpace, "rotation"),
                rate, lifetime, shape(components, unsupported));
    }

    private static Shape shape(JsonObject components, List<String> unsupported) {
        ShapeType type = ShapeType.POINT;
        JsonObject shape = null;
        for (String name : List.of("sphere", "box", "disc", "point", "custom", "entity_aabb")) {
            String key = "minecraft:emitter_shape_" + name;
            if (!components.has(key)) continue;
            shape = object(components, key);
            type = switch (name) {
                case "sphere" -> ShapeType.SPHERE;
                case "box" -> ShapeType.BOX;
                case "disc" -> ShapeType.DISC;
                default -> ShapeType.POINT;
            };
            break;
        }
        if (shape == null) shape = new JsonObject();

        DirectionMode directionMode = DirectionMode.OUTWARDS;
        Vec3 direction = Vec3.ZERO;
        JsonElement directionElement = shape.get("direction");
        if (directionElement != null && directionElement.isJsonArray()) {
            directionMode = DirectionMode.VECTOR;
            direction = vec3(directionElement.getAsJsonArray());
        } else if (directionElement != null && "inwards".equals(directionElement.getAsString())) {
            directionMode = DirectionMode.INWARDS;
        }

        Vec3 planeNormal = new Vec3(MolangExpression.ZERO, MolangExpression.constant(1), MolangExpression.ZERO);
        JsonElement normal = shape.get("plane_normal");
        if (normal != null && normal.isJsonArray()) {
            planeNormal = vec3(normal.getAsJsonArray());
        } else if (normal != null) {
            planeNormal = switch (normal.getAsString()) {
                case "x" -> new Vec3(MolangExpression.constant(1), MolangExpression.ZERO, MolangExpression.ZERO);
                case "z" -> new Vec3(MolangExpression.ZERO, MolangExpression.ZERO, MolangExpression.constant(1));
                default -> planeNormal;
            };
        }

        return new Shape(type, vec3(shape, "offset"), expression(shape, "radius", 1), vec3(shape, "half_dimensions"),
                planeNormal, bool(shape, "surface_only"), directionMode, direction);
    }

    private static Particle particle(JsonObject components, List<String> unsupported) {
        JsonObject initialization = object(components, "minecraft:particle_initialization");
        JsonObject spin = object(components, "minecraft:particle_initial_spin");
        JsonObject dynamic = object(components, "minecraft:particle_motion_dynamic");
        JsonObject lifetime = object(components, "minecraft:particle_lifetime_expression");

        MolangExpression linearSpeed = MolangExpression.ZERO;
        Vec3 velocity = null;
        JsonElement speed = components.get("minecraft:particle_initial_speed");
        if (speed != null && speed.isJsonArray()) velocity = vec3(speed.getAsJsonArray());
        else if (speed != null) linearSpeed = expression(speed, 0);

        MolangExpression maxLifetime = components.has("minecraft:particle_lifetime_expression")
                ? (lifetime.has("max_lifetime") ? expression(lifetime, "max_lifetime", 0) : null)
                : MolangExpression.constant(1);

        return new Particle(
                expression(initialization, "per_update_expression", 0),
                expression(initialization, "per_render_expression", 0),
                linearSpeed, velocity,
                components.has("minecraft:particle_motion_dynamic"),
                vec3(dynamic, "linear_acceleration"),
                expression(dynamic, "linear_drag_coefficient", 0),
                expression(spin, "rotation", 0),
                expression(spin, "rotation_rate", 0),
                expression(dynamic, "rotation_acceleration", 0),
                expression(dynamic, "rotation_drag_coefficient", 0),
                maxLifetime,
                expression(lifetime, "expiration_expression", 0),
                billboard(components, unsupported),
                tint(object(components, "minecraft:particle_appearance_tinting")),
                components.has("minecraft:particle_appearance_lighting"));
    }

    private static Billboard billboard(JsonObject components, List<String> unsupported) {
        boolean present = components.has("minecraft:particle_appearance_billboard");
        JsonObject billboard = object(components, "minecraft:particle_appearance_billboard");
        MolangExpression width = MolangExpression.constant(present ? 0 : 0.2);
        MolangExpression height = width;
        JsonElement size = billboard.get("size");
        if (size != null && size.isJsonArray() && size.getAsJsonArray().size() >= 2) {
            width = expression(size.getAsJsonArray().get(0), 0);
            height = expression(size.getAsJsonArray().get(1), 0);
        }

        FacingMode facing = FacingMode.ROTATE_XYZ;
        String facingName = string(billboard, "facing_camera_mode", "rotate_xyz");
        try {
            facing = FacingMode.valueOf(facingName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            unsupported.add("facing_camera_mode " + facingName);
        }

        JsonObject direction = object(billboard, "direction");
        boolean customDirection = "custom".equals(string(direction, "mode", "derive_from_velocity"));
        double minSpeed = direction.has("min_speed_threshold") ? direction.get("min_speed_threshold").getAsDouble() : 0.01;

        JsonObject uv = object(billboard, "uv");
        int textureWidth = uv.has("texture_width") ? uv.get("texture_width").getAsInt() : 1;
        int textureHeight = uv.has("texture_height") ? uv.get("texture_height").getAsInt() : 1;
        TextureRegion region = null;
        if (uv.has("flipbook")) {
            JsonObject flipbook = uv.getAsJsonObject("flipbook");
            double[] sizeUv = constantPair(flipbook, "size_UV");
            double[] step = constantPair(flipbook, "step_UV");
            MolangExpression[] base = pair(flipbook, "base_UV");
            int frames = (int) Math.max(1, Math.round(expression(flipbook, "max_frame", 1)
                    .evaluate(new MolangContext(new SplittableRandom(0)))));
            region = new TextureRegion(base[0], base[1], sizeUv[0], sizeUv[1], step[0], step[1],
                    expression(flipbook, "frames_per_second", 0), frames,
                    bool(flipbook, "stretch_to_lifetime"), bool(flipbook, "loop"));
        } else if (uv.has("uv") || uv.has("uv_size")) {
            MolangExpression[] base = pair(uv, "uv");
            double[] sizeUv = constantPair(uv, "uv_size");
            region = new TextureRegion(base[0], base[1], sizeUv[0], sizeUv[1], 0, 0,
                    MolangExpression.ZERO, 1, false, false);
        }

        return new Billboard(width, height, facing, customDirection, minSpeed, vec3(direction, "custom_direction"),
                textureWidth, textureHeight, region);
    }

    private static Tint tint(JsonObject tinting) {
        JsonElement color = tinting.get("color");
        if (color == null) return new Tint.Static(0xFFFFFFFF);
        if (color.isJsonPrimitive()) return new Tint.Static(hexColor(color.getAsString()));
        if (color.isJsonArray()) {
            JsonArray channels = color.getAsJsonArray();
            boolean constant = true;
            for (JsonElement channel : channels)
                if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isNumber()) constant = false;
            if (constant) return new Tint.Static(arrayColor(channels));
            return new Tint.Expression(expression(at(channels, 0), 1), expression(at(channels, 1), 1),
                    expression(at(channels, 2), 1), expression(at(channels, 3), 1));
        }
        JsonObject gradientObject = color.getAsJsonObject();
        MolangExpression interpolant = expression(gradientObject, "interpolant", 0);
        JsonElement gradient = gradientObject.get("gradient");
        List<Stop> stops = new ArrayList<>();
        double range = 1;
        if (gradient != null && gradient.isJsonArray()) {
            JsonArray colors = gradient.getAsJsonArray();
            for (int i = 0; i < colors.size(); i++)
                stops.add(new Stop(colors.size() == 1 ? 0 : (double) i / (colors.size() - 1), elementColor(colors.get(i))));
        } else if (gradient != null && gradient.isJsonObject()) {
            for (String key : gradient.getAsJsonObject().keySet()) range = Math.max(range, Double.parseDouble(key));
            for (Map.Entry<String, JsonElement> entry : gradient.getAsJsonObject().entrySet())
                stops.add(new Stop(Double.parseDouble(entry.getKey()) / range, elementColor(entry.getValue())));
            stops.sort(Comparator.comparingDouble(Stop::position));
        }
        if (stops.isEmpty()) return new Tint.Static(0xFFFFFFFF);
        return new Tint.Gradient(List.copyOf(stops), interpolant, range);
    }

    private static Map<String, Curve> curves(JsonObject curves) {
        Map<String, Curve> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : curves.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject curve = entry.getValue().getAsJsonObject();
            CurveType type = switch (string(curve, "type", "linear")) {
                case "catmull_rom" -> CurveType.CATMULL_ROM;
                case "bezier" -> CurveType.BEZIER;
                case "bezier_chain" -> CurveType.BEZIER_CHAIN;
                default -> CurveType.LINEAR;
            };
            double[] nodes = new double[0];
            List<ChainNode> chain = new ArrayList<>();
            JsonElement nodeElement = curve.get("nodes");
            if (nodeElement != null && nodeElement.isJsonArray()) {
                JsonArray array = nodeElement.getAsJsonArray();
                nodes = new double[array.size()];
                for (int i = 0; i < array.size(); i++) nodes[i] = number(array.get(i));
            } else if (nodeElement != null && nodeElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> node : nodeElement.getAsJsonObject().entrySet()) {
                    JsonObject value = node.getValue().getAsJsonObject();
                    double plain = value.has("value") ? number(value.get("value")) : 0;
                    double slope = value.has("slope") ? number(value.get("slope")) : 0;
                    chain.add(new ChainNode(Double.parseDouble(node.getKey()),
                            value.has("left_value") ? number(value.get("left_value")) : plain,
                            value.has("right_value") ? number(value.get("right_value")) : plain,
                            value.has("left_slope") ? number(value.get("left_slope")) : slope,
                            value.has("right_slope") ? number(value.get("right_slope")) : slope));
                }
                chain.sort(Comparator.comparingDouble(ChainNode::time));
            }
            result.put(MolangContext.canonical(entry.getKey()), new Curve(type, expression(curve, "input", 0),
                    expression(curve, "horizontal_range", 1), nodes, List.copyOf(chain)));
        }
        return Map.copyOf(result);
    }

    static int hexColor(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) hex = "FF" + hex;
        if (hex.length() != 8) return 0xFFFFFFFF;
        return (int) Long.parseLong(hex, 16);
    }

    private static int arrayColor(JsonArray channels) {
        double red = channels.size() > 0 ? channels.get(0).getAsDouble() : 0;
        double green = channels.size() > 1 ? channels.get(1).getAsDouble() : 0;
        double blue = channels.size() > 2 ? channels.get(2).getAsDouble() : 0;
        double alpha = channels.size() > 3 ? channels.get(3).getAsDouble() : 1;
        return argb(alpha, red, green, blue);
    }

    public static int argb(double alpha, double red, double green, double blue) {
        return (channel(alpha) << 24) | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    private static int channel(double value) {
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private static int elementColor(JsonElement element) {
        return element.isJsonArray() ? arrayColor(element.getAsJsonArray()) : hexColor(element.getAsString());
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement at(JsonArray array, int index) {
        return index < array.size() ? array.get(index) : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static double number(JsonElement element) {
        try {
            return element.getAsDouble();
        } catch (RuntimeException notNumeric) {
            return 0;
        }
    }

    private static MolangExpression expression(JsonObject object, String key, double fallback) {
        return expression(object.get(key), fallback);
    }

    private static MolangExpression expression(JsonElement element, double fallback) {
        if (element == null || !element.isJsonPrimitive()) return MolangExpression.constant(fallback);
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) return MolangExpression.constant(primitive.getAsDouble());
        if (primitive.isBoolean()) return MolangExpression.constant(primitive.getAsBoolean() ? 1 : 0);
        String text = primitive.getAsString();
        if (text.isBlank()) return MolangExpression.constant(fallback);
        return MolangParser.parse(text);
    }

    private static Vec3 vec3(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? vec3(element.getAsJsonArray()) : Vec3.ZERO;
    }

    private static Vec3 vec3(JsonArray array) {
        return new Vec3(expression(at(array, 0), 0), expression(at(array, 1), 0), expression(at(array, 2), 0));
    }

    private static MolangExpression[] pair(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) return new MolangExpression[]{MolangExpression.ZERO, MolangExpression.ZERO};
        JsonArray array = element.getAsJsonArray();
        return new MolangExpression[]{expression(at(array, 0), 0), expression(at(array, 1), 0)};
    }

    private static double[] constantPair(JsonObject object, String key) {
        MolangExpression[] pair = pair(object, key);
        MolangContext context = new MolangContext(new SplittableRandom(0));
        return new double[]{pair[0].evaluate(context), pair[1].evaluate(context)};
    }
}
