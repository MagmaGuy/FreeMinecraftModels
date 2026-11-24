package com.magmaguy.freeminecraftmodels.seasonal;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.CubeBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Works out where a hat goes on a model and how big it is drawn there, from the two models' cubes. Everything is in
 * Blockbench pixels and Blockbench axes: y up, a model facing -z.
 *
 * <p>The head's largest cube, its skull, gives the hat its direction and a first size. The hat is then lowered onto
 * the whole head: a map of how high the head's cubes reach under each point of the hat, on which the hat comes to rest
 * where enough of it is carried. A helmet, a crown or a hat of the model's own carries it; an antenna, a spike, a horn
 * or a flat plane passes through it, and so does a cube whose top is see-through, such as a cluster of crystals drawn
 * on a clear box. The head is measured where the hat rests, and the hat is scaled so its brim, the widest cube of the
 * hat, is a little wider than that.
 */
final class HatFit {
    /** How much wider than the head the brim is drawn, so the brim's sides clear the head's. */
    private static final float BRIM_OVERHANG = 1.12f;
    /** The share of the brim's height that sinks below where the hat rests, so no gap shows under the hat. */
    private static final float BRIM_SINK = 0.45f;
    /** The furthest the hat is stretched one way more than the other to follow a head that is not square. */
    private static final float MAX_STRETCH = 1.35f;
    /** A skull face this close to level, as the cosine of its tilt, turns the hat with it; past it the hat stands upright. */
    private static final float LEVEL_ENOUGH = (float) Math.cos(Math.toRadians(35));
    /** A skull standing on an edge or a corner counts this much narrower than its outline. */
    private static final float RIDGE_SHARE = 0.7f;
    /** The hat rests at the height that carries at least this share of it; what stands higher on less goes through it. */
    private static final float SUPPORT_SHARE = 0.55f;
    /** The head is measured this far below where the hat rests, as a share of the skull's size. */
    private static final float CROWN_DEPTH = 0.15f;
    /**
     * The hat is fitted to a head no narrower than this share of the skull, and no wider than this many times the
     * skull's larger side: wings, a collar or a mane joined to the head at that height do not widen the hat with them.
     */
    private static final float NARROWEST = 0.5f, WIDEST = 1.3f;
    /**
     * A cube counts towards how wide the head is when its own section is at least this share of the skull's: ears,
     * tufts, bars and trim poke out of the hat's brim instead of widening it.
     */
    private static final float BODY_SHARE = 0.3f;
    /** A cube whose top face is drawn on less than this share of it has no top to carry a hat. */
    private static final float DRAWN_TOP = 0.05f;
    /** A cube thinner than this, in pixels, across either level direction is a flat plane and carries nothing. */
    private static final float MIN_SOLID = 0.2f;
    /** How many steps of the height map span the skull. */
    private static final float MAP_STEPS = 16f;
    /** Thickness a flat cube counts for when cubes are compared by size. */
    private static final float MIN_THICKNESS = 0.5f;

    private static final Pattern HEAD_NAME = Pattern.compile("(h_)?head\\d*");
    private static final Pattern HEAD_WORD = Pattern.compile("(^|[^a-z])head([^a-z]|$)");
    private static final Pattern HAT_WORD = Pattern.compile("(^|[^a-z])hat([^a-z]|$)");

    private HatFit() {
    }

    /** The hat's brim: the point of the hat that stands on a head, and the size of the brim around it. */
    record Brim(Vector3f anchor, float width, float depth, float height) {
    }

    /** Where a hat stands on a head, how it is turned (radians, Blockbench order) and the size it is drawn at. */
    record Placement(Vector3f anchor, Vector3f rotation, Vector3f scale) {
    }

    /** The widest cube of the hat, measured where the hat model has it; null for a hat without cubes. */
    static Brim brim(SkeletonBlueprint hat) {
        Box widest = null;
        for (BoneBlueprint bone : hat.getBoneMap().values())
            for (Box box : boxes(bone))
                if (widest == null || box.footprint() > widest.footprint()
                        || box.footprint() == widest.footprint() && box.low.y < widest.low.y) widest = box;
        if (widest == null || widest.size(0) <= 0 || widest.size(2) <= 0) return null;
        Vector3f anchor = new Vector3f((widest.low.x + widest.high.x) / 2, widest.low.y, (widest.low.z + widest.high.z) / 2);
        return new Brim(anchor, widest.size(0), widest.size(2), Math.max(widest.size(1), 0));
    }

    /**
     * The bones of a model that wear a hat. A bone named head or h_head, with or without a number, is a head, and a
     * model with several wears a hat on each. A model without one wears a single hat on the bone that follows its
     * mob's gaze (h_), or else on a bone with head in its name, whichever has the largest skull. A head with a bone
     * named hat under it already wears one and gets none.
     */
    static List<BoneBlueprint> heads(SkeletonBlueprint model) {
        List<BoneBlueprint> named = new ArrayList<>(), gazing = new ArrayList<>(), worded = new ArrayList<>();
        for (BoneBlueprint bone : model.getBoneMap().values()) {
            if (bone.isNameTag() || bone.isMountPoint() || bone.getParent() == null) continue;
            String name = bone.getOriginalBoneName().toLowerCase(Locale.ROOT);
            if (name.startsWith("tag_") || name.equals("hitbox") || skull(bone) == null) continue;
            // A head that already wears a hat, a bone named hat somewhere under it, keeps the one it has.
            if (wearsHat(bone)) continue;
            if (HEAD_NAME.matcher(name).matches()) named.add(bone);
            else if (bone.isHead()) gazing.add(bone);
            else if (HEAD_WORD.matcher(name).find()) worded.add(bone);
        }
        // A head nested in another head is one head: the outer bone wears the hat.
        named.removeIf(bone -> hasAncestorIn(bone, named));
        named.sort(Comparator.comparing(BoneBlueprint::getOriginalBoneName));
        if (!named.isEmpty()) return named;
        for (List<BoneBlueprint> candidates : List.of(gazing, worded)) {
            BoneBlueprint largest = null;
            candidates.sort(Comparator.comparing(BoneBlueprint::getOriginalBoneName));
            for (BoneBlueprint bone : candidates)
                if (largest == null || skull(bone).volume() > skull(largest).volume()) largest = bone;
            if (largest != null) return List.of(largest);
        }
        return List.of();
    }

    private static boolean wearsHat(BoneBlueprint bone) {
        for (BoneBlueprint child : bone.getBoneBlueprintChildren()) {
            if (child.isNameTag() || child.isAttachment()) continue;
            if (HAT_WORD.matcher(child.getOriginalBoneName().toLowerCase(Locale.ROOT)).find() || wearsHat(child)) return true;
        }
        return false;
    }

    private static boolean hasAncestorIn(BoneBlueprint bone, List<BoneBlueprint> bones) {
        for (BoneBlueprint ancestor = bone.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (bones.contains(ancestor)) return true;
        return false;
    }

    /** Where the hat goes on this head bone; null when the head has no cube to stand it on. */
    static Placement place(BoneBlueprint head, Brim brim, Textures textures) {
        Box skull = skull(head);
        if (skull == null) return null;

        // The head bone's cubes are drawn in the bone's own frame. At rest that frame is turned by the bone's
        // rotation and its parents', so up and forward are taken back into it.
        Quaternionf toHead = restRotation(head).conjugate();
        Vector3f up = toHead.transform(new Vector3f(0, 1, 0));
        Vector3f forward = toHead.transform(new Vector3f(0, 0, -1));

        Vector3f[] axes = skull.axes();
        int top = 0;
        float topSign = 1, level = -1;
        for (int axis = 0; axis < 3; axis++) {
            float dot = axes[axis].dot(up);
            if (Math.abs(dot) > level) {
                level = Math.abs(dot);
                top = axis;
                topSign = Math.signum(dot);
            }
        }

        Vector3f hatUp, hatFront;
        if (level >= LEVEL_ENOUGH) {
            // The hat turns with the skull's face nearest to level, squared up with the skull's sides.
            hatUp = new Vector3f(axes[top]).mul(topSign);
            int front = (top + 1) % 3, side = (top + 2) % 3;
            if (Math.abs(axes[side].dot(forward)) > Math.abs(axes[front].dot(forward))) {
                int swap = front;
                front = side;
                side = swap;
            }
            hatFront = new Vector3f(axes[front]).mul(axes[front].dot(forward) < 0 ? -1 : 1);
        } else {
            // No face is near level: the hat stands upright.
            hatUp = up;
            hatFront = forward;
        }
        Vector3f across = new Vector3f(hatFront).cross(hatUp);

        Span skullSpan = Span.of(skull, across, hatUp, hatFront);
        float narrowed = level >= LEVEL_ENOUGH ? 1 : RIDGE_SHARE;
        float width = Math.max(skullSpan.width() * narrowed, MIN_THICKNESS);
        float depth = Math.max(skullSpan.depth() * narrowed, MIN_THICKNESS);
        float size = (float) Math.sqrt(width * depth);

        // The whole head carries the hat, not the skull alone: the cubes of the head bone and of the bones under it.
        List<Box> carrying = new ArrayList<>();
        for (Box box : subtree(head)) if (box.solid(hatUp) && box.topped(hatUp, textures)) carrying.add(box);
        if (carrying.isEmpty()) carrying.addAll(subtree(head));
        HeightMap heights = HeightMap.of(carrying, across, hatUp, hatFront, size / MAP_STEPS);

        float middle = skullSpan.middleAcross(), centre = skullSpan.middleAlong();
        float rest = heights.rest(middle, centre, width * BRIM_OVERHANG, depth * BRIM_OVERHANG);
        if (Float.isNaN(rest)) rest = skullSpan.top;
        // The head is as wide as its larger cubes are where the hat rests and a little below, through the middle.
        float body = BODY_SHARE * skull.crossSection(hatUp);
        boolean[] section = heights.section(rest - CROWN_DEPTH * size, hatUp, body);
        boolean[] brow = heights.section(rest - 0.02f * size, hatUp, body);
        for (int cell = 0; cell < section.length; cell++) section[cell] |= brow[cell];
        float[] wide = heights.run(section, middle, centre, true), deep = heights.run(section, middle, centre, false);
        float widest = WIDEST * Math.max(skullSpan.width(), skullSpan.depth());
        if (wide != null) {
            float measured = Math.max(NARROWEST * width, Math.min(widest, wide[1] - wide[0]));
            middle = measured > wide[1] - wide[0] ? (wide[0] + wide[1]) / 2
                    : Math.max(wide[0] + measured / 2, Math.min(wide[1] - measured / 2, middle));
            width = measured;
        }
        if (deep != null) {
            float measured = Math.max(NARROWEST * depth, Math.min(widest, deep[1] - deep[0]));
            centre = measured > deep[1] - deep[0] ? (deep[0] + deep[1]) / 2
                    : Math.max(deep[0] + measured / 2, Math.min(deep[1] - measured / 2, centre));
            depth = measured;
        }
        float settled = heights.rest(middle, centre, width * BRIM_OVERHANG, depth * BRIM_OVERHANG);
        if (!Float.isNaN(settled)) rest = settled;
        Vector3f crown = new Vector3f(across).mul(middle).add(new Vector3f(hatUp).mul(rest))
                .add(new Vector3f(hatFront).mul(centre));

        float scaleX = width * BRIM_OVERHANG / brim.width(), scaleZ = depth * BRIM_OVERHANG / brim.depth();
        // A hat stretched too far one way stops looking like the hat, so the narrow side gives instead.
        scaleX = Math.max(scaleX, scaleZ / MAX_STRETCH);
        scaleZ = Math.max(scaleZ, scaleX / MAX_STRETCH);
        float scaleY = (float) Math.sqrt(scaleX * scaleZ);
        Vector3f anchor = crown.sub(new Vector3f(hatUp).mul(BRIM_SINK * brim.height() * scaleY));

        // The hat model faces -z with y up, so its axes map onto the head's right, up and back.
        Vector3f back = new Vector3f(hatFront).negate();
        Vector3f rotation = new Quaternionf().setFromNormalized(new Matrix3f(across, hatUp, back))
                .getEulerAnglesZYX(new Vector3f());
        return new Placement(anchor, rotation, new Vector3f(scaleX, scaleY, scaleZ));
    }

    /**
     * How the head bone's own frame is turned at rest: its rotation after its parents'. A bone that follows its mob's
     * gaze drops its own rotation and every rotation above it, since the gaze replaces them, so only the rotations
     * below such a bone count.
     */
    private static Quaternionf restRotation(BoneBlueprint head) {
        Quaternionf rotation = new Quaternionf();
        for (BoneBlueprint bone = head; bone != null && !bone.isHead(); bone = bone.getParent()) {
            Vector3f angles = bone.getBlueprintOriginalBoneRotation();
            rotation.premul(new Quaternionf().rotationZYX(angles.z, angles.y, angles.x));
        }
        return rotation;
    }

    /** The cubes of a bone and of every bone under it, in the bone's own frame; the bones under it count unturned. */
    private static List<Box> subtree(BoneBlueprint head) {
        List<Box> boxes = boxes(head);
        collectBelow(head, boxes);
        return boxes;
    }

    /** The largest cube of the bone, or of the bones under it when it has none of its own. */
    private static Box skull(BoneBlueprint head) {
        List<Box> boxes = boxes(head);
        if (boxes.isEmpty()) collectBelow(head, boxes);
        Box largest = null;
        for (Box box : boxes) if (largest == null || box.volume() > largest.volume()) largest = box;
        return largest;
    }

    private static void collectBelow(BoneBlueprint bone, List<Box> boxes) {
        for (BoneBlueprint child : bone.getBoneBlueprintChildren()) {
            if (child.isNameTag() || child.isMountPoint()) continue;
            boxes.addAll(boxes(child));
            collectBelow(child, boxes);
        }
    }

    private static List<Box> boxes(BoneBlueprint bone) {
        List<Box> boxes = new ArrayList<>();
        for (CubeBlueprint cube : bone.getCubeBlueprintChildren()) {
            Map<String, Object> json = cube.getOriginalCubeJSON();
            Vector3f from = vector(json.get("from")), to = vector(json.get("to"));
            if (from == null || to == null) continue;
            float inflate = json.get("inflate") instanceof Number number ? number.floatValue() : 0;
            Vector3f low = new Vector3f(from).min(to).sub(inflate, inflate, inflate);
            Vector3f high = new Vector3f(from).max(to).add(inflate, inflate, inflate);
            Vector3f angles = vector(json.get("rotation")), origin = vector(json.get("origin"));
            Quaternionf rotation = angles == null ? new Quaternionf() : new Quaternionf().rotationZYX(
                    (float) Math.toRadians(angles.z), (float) Math.toRadians(angles.y), (float) Math.toRadians(angles.x));
            boxes.add(new Box(low, high, rotation, origin == null ? new Vector3f() : origin, json.get("faces")));
        }
        return boxes;
    }

    private static Vector3f vector(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 3) return null;
        float[] numbers = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            if (!(list.get(axis) instanceof Number number)) return null;
            numbers[axis] = number.floatValue();
        }
        return new Vector3f(numbers);
    }

    /** How far a cube reaches across, up and along the hat's own directions. */
    private record Span(float left, float right, float bottom, float top, float back, float front) {
        static Span of(Box box, Vector3f across, Vector3f up, Vector3f along) {
            float[] low = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] high = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            Vector3f[] directions = {across, up, along};
            for (Vector3f corner : box.corners())
                for (int axis = 0; axis < 3; axis++) {
                    float reach = corner.dot(directions[axis]);
                    low[axis] = Math.min(low[axis], reach);
                    high[axis] = Math.max(high[axis], reach);
                }
            return new Span(low[0], high[0], low[1], high[1], low[2], high[2]);
        }

        float width() {
            return right - left;
        }

        float depth() {
            return front - back;
        }

        float middleAcross() {
            return (left + right) / 2;
        }

        float middleAlong() {
            return (back + front) / 2;
        }

        boolean covers(float across, float along) {
            return across >= left && across <= right && along >= back && along <= front;
        }
    }

    /** How high a head's cubes reach over each point across and along the hat's directions. */
    private static final class HeightMap {
        private final float step, left, back;
        private final int columns, rows;
        private final float[] tops;
        private final List<Box> boxes = new ArrayList<>();
        private final List<Span> spans = new ArrayList<>();
        private Vector3f across, up, along;

        private HeightMap(float step, float left, float back, int columns, int rows) {
            this.step = step;
            this.left = left;
            this.back = back;
            this.columns = columns;
            this.rows = rows;
            this.tops = new float[columns * rows];
            java.util.Arrays.fill(tops, Float.NEGATIVE_INFINITY);
        }

        static HeightMap of(List<Box> boxes, Vector3f across, Vector3f up, Vector3f along, float step) {
            List<Span> spans = new ArrayList<>();
            float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
            float back = Float.POSITIVE_INFINITY, front = Float.NEGATIVE_INFINITY;
            for (Box box : boxes) {
                Span span = Span.of(box, across, up, along);
                spans.add(span);
                left = Math.min(left, span.left);
                right = Math.max(right, span.right);
                back = Math.min(back, span.back);
                front = Math.max(front, span.front);
            }
            step = Math.max(step, 0.125f);
            // A head with far-flung parts gets a coarser map instead of a huge one.
            while ((right - left) / step * ((front - back) / step) > 90_000f) step *= 1.5f;
            int columns = Math.max(1, (int) Math.ceil((right - left) / step)), rows = Math.max(1, (int) Math.ceil((front - back) / step));
            HeightMap map = new HeightMap(step, left, back, columns, rows);
            map.boxes.addAll(boxes);
            map.spans.addAll(spans);
            map.across = across;
            map.up = up;
            map.along = along;
            map.each((box, cell, reach) -> {
                if (reach[1] > map.tops[cell]) map.tops[cell] = reach[1];
            });
            return map;
        }

        /** Runs over every cube and every step of the map under it, with where the line up from there crosses it. */
        private interface Crossing {
            void at(Box box, int cell, float[] reach);
        }

        private void each(Crossing crossing) {
            for (int index = 0; index < boxes.size(); index++) {
                Span span = spans.get(index);
                int firstColumn = column(span.left), lastColumn = column(span.right);
                int firstRow = row(span.back), lastRow = row(span.front);
                for (int row = firstRow; row <= lastRow; row++)
                    for (int column = firstColumn; column <= lastColumn; column++) {
                        Vector3f point = new Vector3f(across).mul(left + (column + 0.5f) * step)
                                .add(new Vector3f(along).mul(back + (row + 0.5f) * step));
                        float[] reach = boxes.get(index).reach(point, up);
                        if (reach != null) crossing.at(boxes.get(index), row * columns + column, reach);
                    }
            }
        }

        /**
         * Where the head is solid at one height, counting the cubes whose own section across {@code up} is at least
         * {@code body}: a cube floating above that height is no part of it there.
         */
        boolean[] section(float level, Vector3f up, float body) {
            boolean[] solid = new boolean[columns * rows];
            each((box, cell, reach) -> {
                if (reach[0] <= level && reach[1] >= level && box.crossSection(up) >= body) solid[cell] = true;
            });
            return solid;
        }

        private int column(float across) {
            return Math.max(0, Math.min(columns - 1, (int) Math.floor((across - left) / step)));
        }

        private int row(float along) {
            return Math.max(0, Math.min(rows - 1, (int) Math.floor((along - back) / step)));
        }

        /**
         * The height a hat of this outline comes to rest at: the highest level that carries at least
         * {@link #SUPPORT_SHARE} of it. NaN when nothing of the head is under it.
         */
        float rest(float middle, float centre, float width, float depth) {
            List<Float> under = new ArrayList<>();
            for (int row = 0; row < rows; row++) {
                float along = back + (row + 0.5f) * step;
                if (Math.abs(along - centre) > depth / 2) continue;
                for (int column = 0; column < columns; column++) {
                    float across = left + (column + 0.5f) * step;
                    if (Math.abs(across - middle) <= width / 2) under.add(tops[row * columns + column]);
                }
            }
            // The hat's outline also reaches past the map, over nothing.
            int outline = Math.max(under.size(), Math.round(width / step) * Math.round(depth / step));
            under.sort(Comparator.reverseOrder());
            int carried = Math.max(0, (int) Math.ceil(SUPPORT_SHARE * outline) - 1);
            if (carried < under.size() && under.get(carried) != Float.NEGATIVE_INFINITY) return under.get(carried);
            // Too little of the head is under the hat to carry that share: it rests on the highest there is.
            return under.isEmpty() || under.get(0) == Float.NEGATIVE_INFINITY ? Float.NaN : under.get(0);
        }

        /**
         * How far a section of the head reaches through a point, across or along: its stretch that holds the point,
         * on the line through it or one beside it. Null when the section does not hold the point.
         */
        float[] run(boolean[] section, float middle, float centre, boolean acrossways) {
            int column = column(middle), row = row(centre);
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (int beside = -1; beside <= 1; beside++) {
                int fixed = (acrossways ? row : column) + beside, start = acrossways ? column : row;
                int length = acrossways ? columns : rows;
                if (!holds(section, acrossways, start, fixed)) continue;
                int first = start, last = start;
                // A seam one step wide between two cubes does not end the head.
                while (first > 0 && (holds(section, acrossways, first - 1, fixed) || holds(section, acrossways, first - 2, fixed))) first--;
                while (last < length - 1 && (holds(section, acrossways, last + 1, fixed) || holds(section, acrossways, last + 2, fixed))) last++;
                while (first < start && !holds(section, acrossways, first, fixed)) first++;
                while (last > start && !holds(section, acrossways, last, fixed)) last--;
                float origin = acrossways ? left : back;
                low = Math.min(low, origin + first * step);
                high = Math.max(high, origin + (last + 1) * step);
            }
            return low > high ? null : new float[]{low, high};
        }

        private boolean holds(boolean[] section, boolean acrossways, int moving, int fixed) {
            int column = acrossways ? moving : fixed, row = acrossways ? fixed : moving;
            return column >= 0 && row >= 0 && column < columns && row < rows && section[row * columns + column];
        }
    }

    /** A model's texture images, which tell a cube with a drawn top from one whose top is see-through. */
    static final class Textures {
        private final Map<Integer, BufferedImage> images = new HashMap<>();
        private final Map<Integer, Float> pixelsPerUnit = new HashMap<>();

        /** Reads the textures the model wrote into the resource pack; a texture that cannot be read counts as drawn. */
        static Textures of(FileModelConverter model) {
            Textures textures = new Textures();
            if (model.getParsedTextures() == null || MetadataHandler.PLUGIN == null) return textures;
            File folder = new File(MetadataHandler.PLUGIN.getDataFolder(),
                    "output/FreeMinecraftModels/assets/freeminecraftmodels/textures/entity/" + model.getID());
            for (ParsedTexture texture : model.getParsedTextures()) {
                try {
                    BufferedImage image = ImageIO.read(new File(folder, texture.getFilename()));
                    if (image == null || texture.getTextureWidth() <= 0) continue;
                    textures.images.put(texture.getId(), image);
                    textures.pixelsPerUnit.put(texture.getId(), (float) (image.getWidth() / texture.getTextureWidth()));
                } catch (Exception unreadable) {
                    // Left out: its faces count as drawn.
                }
            }
            return textures;
        }

        /** The share of a face that is drawn rather than see-through; 1 when it cannot be told. */
        float drawn(Object faceJson) {
            if (!(faceJson instanceof Map<?, ?> face) || !(face.get("texture") instanceof Number texture)
                    || !(face.get("uv") instanceof List<?> uv) || uv.size() < 4) return 1;
            BufferedImage image = images.get(texture.intValue());
            if (image == null) return 1;
            float scale = pixelsPerUnit.get(texture.intValue());
            float[] corners = new float[4];
            for (int corner = 0; corner < 4; corner++) {
                if (!(uv.get(corner) instanceof Number number)) return 1;
                corners[corner] = number.floatValue() * scale;
            }
            int left = Math.max(0, (int) Math.floor(Math.min(corners[0], corners[2])));
            int right = Math.min(image.getWidth(), Math.max(left + 1, (int) Math.ceil(Math.max(corners[0], corners[2]))));
            int top = Math.max(0, (int) Math.floor(Math.min(corners[1], corners[3])));
            int bottom = Math.min(image.getHeight(), Math.max(top + 1, (int) Math.ceil(Math.max(corners[1], corners[3]))));
            int pixels = 0, drawn = 0;
            for (int y = top; y < bottom; y++)
                for (int x = left; x < right; x++) {
                    pixels++;
                    if ((image.getRGB(x, y) >>> 24) > 24) drawn++;
                }
            return pixels == 0 ? 1 : drawn / (float) pixels;
        }
    }

    /** A cube as Blockbench holds it: a box turned about its own pivot. */
    private record Box(Vector3f low, Vector3f high, Quaternionf rotation, Vector3f pivot, Object faces) {
        private static final String[][] FACES = {{"west", "east"}, {"down", "up"}, {"north", "south"}};

        /** Whether the face of the cube that looks {@code up} is drawn: a cube with a see-through top has no top. */
        boolean topped(Vector3f up, Textures textures) {
            if (textures == null || !(faces instanceof Map<?, ?> map)) return true;
            Vector3f[] axes = axes();
            int upright = 0;
            for (int axis = 1; axis < 3; axis++)
                if (Math.abs(axes[axis].dot(up)) > Math.abs(axes[upright].dot(up))) upright = axis;
            return textures.drawn(map.get(FACES[upright][axes[upright].dot(up) < 0 ? 0 : 1])) >= DRAWN_TOP;
        }

        float size(int axis) {
            return high.get(axis) - low.get(axis);
        }

        float footprint() {
            return size(0) * size(2);
        }

        float volume() {
            return Math.max(size(0), MIN_THICKNESS) * Math.max(size(1), MIN_THICKNESS) * Math.max(size(2), MIN_THICKNESS);
        }

        /** The area of the cube's own section across {@code up}: its two sides that lie most nearly level. */
        float crossSection(Vector3f up) {
            Vector3f[] axes = axes();
            int upright = 0;
            for (int axis = 1; axis < 3; axis++)
                if (Math.abs(axes[axis].dot(up)) > Math.abs(axes[upright].dot(up))) upright = axis;
            return size((upright + 1) % 3) * size((upright + 2) % 3);
        }

        /** Whether the cube has thickness both ways across {@code up}: a flat plane standing on edge has none. */
        boolean solid(Vector3f up) {
            Vector3f[] axes = axes();
            int upright = 0;
            for (int axis = 1; axis < 3; axis++)
                if (Math.abs(axes[axis].dot(up)) > Math.abs(axes[upright].dot(up))) upright = axis;
            return size((upright + 1) % 3) > MIN_SOLID && size((upright + 2) % 3) > MIN_SOLID;
        }

        /**
         * Where the line up from a point of the plane through the origin across {@code up} enters and leaves the
         * cube, as heights along {@code up}; null where it misses the cube.
         */
        float[] reach(Vector3f point, Vector3f up) {
            Quaternionf back = new Quaternionf(rotation).conjugate();
            Vector3f start = back.transform(new Vector3f(point).sub(pivot)).add(pivot);
            Vector3f direction = back.transform(new Vector3f(up));
            float enter = Float.NEGATIVE_INFINITY, leave = Float.POSITIVE_INFINITY;
            for (int axis = 0; axis < 3; axis++) {
                float from = start.get(axis), along = direction.get(axis);
                if (Math.abs(along) < 1e-6f) {
                    if (from < low.get(axis) || from > high.get(axis)) return null;
                    continue;
                }
                float first = (low.get(axis) - from) / along, second = (high.get(axis) - from) / along;
                enter = Math.max(enter, Math.min(first, second));
                leave = Math.min(leave, Math.max(first, second));
            }
            return enter <= leave ? new float[]{enter, leave} : null;
        }

        /** The directions of the cube's sides once turned. */
        Vector3f[] axes() {
            return new Vector3f[]{rotation.transform(new Vector3f(1, 0, 0)), rotation.transform(new Vector3f(0, 1, 0)),
                    rotation.transform(new Vector3f(0, 0, 1))};
        }

        Vector3f center() {
            return turned(new Vector3f(low).add(high).mul(0.5f));
        }

        List<Vector3f> corners() {
            List<Vector3f> corners = new ArrayList<>();
            for (int corner = 0; corner < 8; corner++)
                corners.add(turned(new Vector3f((corner & 1) == 0 ? low.x : high.x, (corner & 2) == 0 ? low.y : high.y,
                        (corner & 4) == 0 ? low.z : high.z)));
            return corners;
        }

        private Vector3f turned(Vector3f point) {
            return rotation.transform(point.sub(pivot)).add(pivot);
        }
    }
}
