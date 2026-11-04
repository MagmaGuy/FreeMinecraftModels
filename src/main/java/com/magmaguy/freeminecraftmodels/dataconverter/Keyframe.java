package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangExpression;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangParser;
import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * One Blockbench keyframe. An axis is either the number the artist typed or a MoLang expression compiled once
 * here and shared by every entity of the model; an expression depends on the entity playing it, so its channel
 * is sampled per tick instead of baked.
 * <p>
 * A keyframe holds one data point, or two when the artist separated its pre and post values: the pre value is
 * the one reached on arrival, the post value the one the next segment leaves from.
 */
public class Keyframe {
    /** Data point index of the value reached on arrival. */
    public static final int PRE = 0;
    /** Data point index of the value the next segment leaves from; the pre value when there is only one. */
    public static final int POST = 1;
    // Blockbench's handles on a keyframe saved without them: 0.1 s on either side, no value offset.
    private static final double DEFAULT_HANDLE_TICKS = 2D;

    @Getter
    private final TransformationType transformationType;
    @Getter
    private final int timeInTicks;
    @Getter
    private final double exactTimeInTicks;
    @Getter
    private final InterpolationType interpolationType;
    // Per data point and axis (x, y, z): the constant, or for an expression axis its compiled program and
    // authored text, and whether that program assigns a variable.
    private final float[][] data = new float[2][3];
    private final MolangExpression[][] expressions = new MolangExpression[2][3];
    private final String[][] sources = new String[2][3];
    private final boolean[][] assigns = new boolean[2][3];
    @Getter
    private int pointCount = 1;
    // Bezier handles per axis: time offsets in ticks from this keyframe, value offsets in Blockbench units.
    private final double[] bezierLeftTime = {-DEFAULT_HANDLE_TICKS, -DEFAULT_HANDLE_TICKS, -DEFAULT_HANDLE_TICKS};
    private final double[] bezierLeftValue = new double[3];
    private final double[] bezierRightTime = {DEFAULT_HANDLE_TICKS, DEFAULT_HANDLE_TICKS, DEFAULT_HANDLE_TICKS};
    private final double[] bezierRightValue = new double[3];

    public Keyframe(Object object, String modelName, String animationName) {
        Map<String, Object> data = (Map<String, Object>) object;
        transformationType = TransformationType.valueOf(((String) data.get("channel")).toUpperCase());
        interpolationType = InterpolationType.valueOf(((String) data.get("interpolation")).toUpperCase());
        exactTimeInTicks = 20 * (double) data.get("time");
        if (!Double.isFinite(exactTimeInTicks)) {
            throw new IllegalArgumentException("Keyframe time must be finite");
        }
        timeInTicks = (int) Math.floor(exactTimeInTicks);
        List<Map<String, Object>> dataPoints = (List<Map<String, Object>>) data.get("data_points");

        String[] axes = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++)
            parseAxis(PRE, axis, dataPoints.get(0).get(axes[axis]), modelName, animationName);
        if (dataPoints.size() > 1) {
            // A post value Blockbench would not have written is ignored rather than failing the whole track.
            try {
                for (int axis = 0; axis < 3; axis++)
                    parseAxis(POST, axis, dataPoints.get(1).get(axes[axis]), modelName, animationName);
                pointCount = 2;
            } catch (RuntimeException unreadablePostValue) {
                pointCount = 1;
            }
        }

        readHandle(data.get("bezier_left_time"), bezierLeftTime, 20);
        readHandle(data.get("bezier_left_value"), bezierLeftValue, 1);
        readHandle(data.get("bezier_right_time"), bezierRightTime, 20);
        readHandle(data.get("bezier_right_value"), bezierRightValue, 1);
    }

    public Keyframe(TransformationType transformationType, int timeInTicks, InterpolationType interpolationType, float dataX, float dataY, float dataZ) {
        this(transformationType, (double) timeInTicks, interpolationType, dataX, dataY, dataZ);
    }

    Keyframe(TransformationType transformationType, double exactTimeInTicks, InterpolationType interpolationType, float dataX, float dataY, float dataZ) {
        this.transformationType = transformationType;
        this.exactTimeInTicks = exactTimeInTicks;
        this.timeInTicks = (int) Math.floor(exactTimeInTicks);
        this.interpolationType = interpolationType;
        data[PRE][0] = dataX;
        data[PRE][1] = dataY;
        data[PRE][2] = dataZ;
    }

    private static void readHandle(Object raw, double[] target, double scale) {
        if (!(raw instanceof List<?> values)) return;
        for (int axis = 0; axis < 3 && axis < values.size(); axis++)
            if (values.get(axis) instanceof Number number && Double.isFinite(number.doubleValue()))
                target[axis] = number.doubleValue() * scale;
    }

    private void parseAxis(int point, int axis, Object rawObject, String modelName, String animationName) {
        if (!(rawObject instanceof String rawValue)) {
            data[point][axis] = ((Double) rawObject).floatValue();
            return;
        }
        String source = rawValue.trim();
        if (source.isEmpty()) {
            data[point][axis] = transformationType == TransformationType.SCALE ? 1f : 0f;
            return;
        }
        try {
            data[point][axis] = (float) Double.parseDouble(source);
            return;
        } catch (NumberFormatException notANumber) {
            // Anything Blockbench accepts that is not a number is MoLang.
        }
        // Written and previewed in Blockbench, where a program's last line is its value even without return.
        source = MolangParser.returningLastLine(source);
        try {
            expressions[point][axis] = MolangParser.parse(source);
            sources[point][axis] = source;
            assigns[point][axis] = MolangParser.assignsVariables(source);
        } catch (IllegalArgumentException malformed) {
            Logger.warn("Failed to parse the keyframe expression '" + source + "' in animation " + animationName
                    + " for model " + modelName + ": " + malformed.getMessage() + ". That value plays as 0.");
        }
    }

    public float getDataX() {
        return data[PRE][0];
    }

    public float getDataY() {
        return data[PRE][1];
    }

    public float getDataZ() {
        return data[PRE][2];
    }

    /** The pre value's constant on an axis (0 = x, 1 = y, 2 = z); 0 on an expression axis. */
    public float getData(int axis) {
        return data[PRE][axis];
    }

    /** The pre value's authored MoLang on an axis, or null where the artist typed a number. */
    public String getSource(int axis) {
        return sources[PRE][axis];
    }

    /** The left handle's time offset on an axis, in ticks before this keyframe (negative). */
    public double getBezierLeftTime(int axis) {
        return bezierLeftTime[axis];
    }

    /** The left handle's value offset on an axis, in Blockbench units. */
    public double getBezierLeftValue(int axis) {
        return bezierLeftValue[axis];
    }

    /** The right handle's time offset on an axis, in ticks after this keyframe. */
    public double getBezierRightTime(int axis) {
        return bezierRightTime[axis];
    }

    /** The right handle's value offset on an axis, in Blockbench units. */
    public double getBezierRightValue(int axis) {
        return bezierRightValue[axis];
    }

    /** Whether an expression axis assigns a variable, which later evaluations can read. */
    public boolean assignsVariables() {
        for (int point = 0; point < pointCount; point++)
            for (boolean assigning : assigns[point]) if (assigning) return true;
        return false;
    }

    /** Whether any axis is an expression, which keeps the whole channel from being baked at load. */
    public boolean isDynamic() {
        for (int point = 0; point < pointCount; point++)
            for (MolangExpression expression : expressions[point]) if (expression != null) return true;
        return false;
    }

    /** The pre value on an axis; see {@link #value(int, int, MolangContext)}. */
    public double value(int axis, MolangContext context) {
        return value(axis, PRE, context);
    }

    /**
     * The value on an axis at a data point ({@link #PRE} or {@link #POST}; POST reads the pre value when the
     * keyframe has only one): the constant, or the expression evaluated now. The context may be null for a
     * keyframe without expressions. A result that is not a number plays as 0 so it cannot poison the pose.
     */
    public double value(int axis, int point, MolangContext context) {
        int index = Math.min(point, pointCount - 1);
        MolangExpression expression = expressions[index][axis];
        if (expression == null) return data[index][axis];
        context.clearTemporaries();
        return finite(expression.evaluate(context));
    }

    /**
     * The value {@link #value(int, int, MolangContext)} returns, without keeping any variable the expression
     * assigns. A keyframe read only to shape the curve of a neighbouring segment uses this, so its assignments
     * take effect only on the ticks of the segments it starts or ends.
     */
    public double valueWithoutAssignments(int axis, int point, MolangContext context) {
        int index = Math.min(point, pointCount - 1);
        if (!assigns[index][axis]) return value(axis, point, context);
        context.clearTemporaries();
        // Reads fall through to the entity's variables; writes stay in the scratch context.
        return finite(expressions[index][axis].evaluate(new MolangContext(context, context.random())));
    }

    private static double finite(double result) {
        return Double.isFinite(result) ? result : 0;
    }
}
