package com.magmaguy.freeminecraftmodels.seasonal;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.CubeBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Works out where a hat goes on a model and how big it is drawn there, from the two models' cubes. Everything is in
 * Blockbench pixels and Blockbench axes: y up, a model facing -z.
 *
 * <p>The head is measured by its skull, the largest cube of the head bone. The hat stands on the skull's top face,
 * turned with it, and is scaled so its brim, the widest cube of the hat, is a little wider than that face.
 */
final class HatFit {
    /** How much wider than the skull the brim is drawn, so the brim's sides clear the head's. */
    private static final float BRIM_OVERHANG = 1.0625f;
    /** The share of the brim's height that sinks below the top of the skull, so no gap shows under the hat. */
    private static final float BRIM_SINK = 0.25f;
    /** The furthest the hat is stretched one way more than the other to follow a skull that is not square. */
    private static final float MAX_STRETCH = 1.35f;
    /** A skull face this close to level, as the cosine of its tilt, carries the hat; past it the hat stands upright. */
    private static final float LEVEL_ENOUGH = (float) Math.cos(Math.toRadians(35));
    /** On a skull standing on an edge or a corner the hat sits on a ridge, so it is drawn this much narrower. */
    private static final float RIDGE_SHARE = 0.7f;
    /** A cube carries the hat when it covers at least this share of the skull's top. */
    private static final float CARRYING_SHARE = 0.25f;
    /** A cube rests on the one below when it starts no further than this above it, in pixels. */
    private static final float RESTING_GAP = 0.75f;
    /** The hat is fitted to a top between these shares of the skull's own width. */
    private static final float NARROWEST = 0.6f, WIDEST = 1.3f;
    /** Thickness a flat cube counts for when cubes are compared by size. */
    private static final float MIN_THICKNESS = 0.5f;

    private static final Pattern HEAD_NAME = Pattern.compile("(h_)?head\\d*");
    private static final Pattern HEAD_WORD = Pattern.compile("(^|[^a-z])head([^a-z]|$)");

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
     * mob's gaze (h_), or else on a bone with head in its name, whichever has the largest skull.
     */
    static List<BoneBlueprint> heads(SkeletonBlueprint model) {
        List<BoneBlueprint> named = new ArrayList<>(), gazing = new ArrayList<>(), worded = new ArrayList<>();
        for (BoneBlueprint bone : model.getBoneMap().values()) {
            if (bone.isNameTag() || bone.isMountPoint() || bone.getParent() == null) continue;
            String name = bone.getOriginalBoneName().toLowerCase(Locale.ROOT);
            if (name.startsWith("tag_") || name.equals("hitbox") || skull(bone) == null) continue;
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

    private static boolean hasAncestorIn(BoneBlueprint bone, List<BoneBlueprint> bones) {
        for (BoneBlueprint ancestor = bone.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (bones.contains(ancestor)) return true;
        return false;
    }

    /** Where the hat goes on this head bone; null when the head has no cube to stand it on. */
    static Placement place(BoneBlueprint head, Brim brim) {
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

        Vector3f hatUp, hatFront, crown;
        float width, depth;
        if (level >= LEVEL_ENOUGH) {
            // The hat stands on the face nearest to level, squared up with the skull's sides.
            hatUp = new Vector3f(axes[top]).mul(topSign);
            int front = (top + 1) % 3, side = (top + 2) % 3;
            if (Math.abs(axes[side].dot(forward)) > Math.abs(axes[front].dot(forward))) {
                int swap = front;
                front = side;
                side = swap;
            }
            hatFront = new Vector3f(axes[front]).mul(axes[front].dot(forward) < 0 ? -1 : 1);
            Vector3f right = new Vector3f(hatFront).cross(hatUp);

            // A head often carries more on top of its skull: a helmet, a hat of its own, hair, a crest. The hat
            // climbs onto whatever rests on the spot it stands on and is big enough to carry it; thinner things,
            // such as a spike or the tip of a pointed hat, end up inside the hat instead.
            Span skullSpan = Span.of(skull, right, hatUp, hatFront), support = skullSpan;
            List<Box> carried = new ArrayList<>(boxes(head));
            collectBelow(head, carried);
            List<Span> spans = new ArrayList<>();
            for (Box box : carried) spans.add(Span.of(box, right, hatUp, hatFront));
            float middleAcross = skullSpan.middleAcross(), middleAlong = skullSpan.middleAlong();
            while (true) {
                Span higher = null;
                for (Span span : spans) {
                    if (span.top <= support.top + 0.05f || span.bottom > support.top + RESTING_GAP) continue;
                    if (!span.covers(middleAcross, middleAlong)) continue;
                    if (span.width() * span.depth() < CARRYING_SHARE * skullSpan.width() * skullSpan.depth()) continue;
                    if (higher == null || span.top > higher.top) higher = span;
                }
                if (higher == null) break;
                support = higher;
            }
            // The hat keeps to the skull's size: a little smaller on a narrow top, a little bigger on a wide one.
            width = Math.max(NARROWEST * skullSpan.width(), Math.min(WIDEST * skullSpan.width(), support.width()));
            depth = Math.max(NARROWEST * skullSpan.depth(), Math.min(WIDEST * skullSpan.depth(), support.depth()));
            // On a top narrower than the skull the hat centres on that top, otherwise on the skull.
            float across = support.width() < skullSpan.width() ? support.middleAcross() : middleAcross;
            float along = support.depth() < skullSpan.depth() ? support.middleAlong() : middleAlong;
            crown = new Vector3f(right).mul(across).add(new Vector3f(hatUp).mul(support.top))
                    .add(new Vector3f(hatFront).mul(along));
        } else {
            // No face is near level: the hat stands upright on the skull's highest edge or corner.
            hatUp = up;
            hatFront = forward;
            Vector3f right = new Vector3f(hatFront).cross(hatUp);
            List<Vector3f> corners = skull.corners();
            float highest = Float.NEGATIVE_INFINITY;
            float[] across = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}, along = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (Vector3f corner : corners) {
                highest = Math.max(highest, corner.dot(hatUp));
                across[0] = Math.min(across[0], corner.dot(right));
                across[1] = Math.max(across[1], corner.dot(right));
                along[0] = Math.min(along[0], corner.dot(hatFront));
                along[1] = Math.max(along[1], corner.dot(hatFront));
            }
            crown = new Vector3f();
            int highCorners = 0;
            for (Vector3f corner : corners)
                if (corner.dot(hatUp) >= highest - 0.01f) {
                    crown.add(corner);
                    highCorners++;
                }
            crown.div(highCorners);
            width = (across[1] - across[0]) * RIDGE_SHARE;
            depth = (along[1] - along[0]) * RIDGE_SHARE;
        }
        if (width <= 0 && depth <= 0) return null;

        float scaleX = width * BRIM_OVERHANG / brim.width(), scaleZ = depth * BRIM_OVERHANG / brim.depth();
        // A hat stretched too far one way stops looking like the hat, so the narrow side gives instead.
        scaleX = Math.max(scaleX, scaleZ / MAX_STRETCH);
        scaleZ = Math.max(scaleZ, scaleX / MAX_STRETCH);
        float scaleY = (float) Math.sqrt(scaleX * scaleZ);
        Vector3f anchor = crown.sub(new Vector3f(hatUp).mul(BRIM_SINK * brim.height() * scaleY));

        // The hat model faces -z with y up, so its axes map onto the head's right, up and back.
        Vector3f back = new Vector3f(hatFront).negate();
        Vector3f right = new Vector3f(hatUp).cross(back);
        Vector3f rotation = new Quaternionf().setFromNormalized(new Matrix3f(right, hatUp, back))
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
            boxes.add(new Box(low, high, rotation, origin == null ? new Vector3f() : origin));
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

    /** A cube as Blockbench holds it: a box turned about its own pivot. */
    private record Box(Vector3f low, Vector3f high, Quaternionf rotation, Vector3f pivot) {
        float size(int axis) {
            return high.get(axis) - low.get(axis);
        }

        float footprint() {
            return size(0) * size(2);
        }

        float volume() {
            return Math.max(size(0), MIN_THICKNESS) * Math.max(size(1), MIN_THICKNESS) * Math.max(size(2), MIN_THICKNESS);
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
