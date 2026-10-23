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
 */
public class Keyframe {
    @Getter
    private final TransformationType transformationType;
    @Getter
    private final int timeInTicks;
    @Getter
    private final double exactTimeInTicks;
    @Getter
    private final InterpolationType interpolationType;
    // Per axis (x, y, z): the constant, or for an expression axis its compiled program and authored text.
    private final float[] data = new float[3];
    private final MolangExpression[] expressions = new MolangExpression[3];
    private final String[] sources = new String[3];

    public Keyframe(Object object, String modelName, String animationName) {
        Map<String, Object> data = (Map<String, Object>) object;
        transformationType = TransformationType.valueOf(((String) data.get("channel")).toUpperCase());
        interpolationType = InterpolationType.valueOf(((String) data.get("interpolation")).toUpperCase());
        exactTimeInTicks = 20 * (double) data.get("time");
        if (!Double.isFinite(exactTimeInTicks)) {
            throw new IllegalArgumentException("Keyframe time must be finite");
        }
        timeInTicks = (int) Math.floor(exactTimeInTicks);
        Map<String, Object> dataPoints = ((List<Map<String, Object>>) data.get("data_points")).get(0);

        String[] axes = {"x", "y", "z"};
        for (int axis = 0; axis < 3; axis++) parseAxis(axis, dataPoints.get(axes[axis]), modelName, animationName);
    }

    public Keyframe(TransformationType transformationType, int timeInTicks, InterpolationType interpolationType, float dataX, float dataY, float dataZ) {
        this(transformationType, (double) timeInTicks, interpolationType, dataX, dataY, dataZ);
    }

    Keyframe(TransformationType transformationType, double exactTimeInTicks, InterpolationType interpolationType, float dataX, float dataY, float dataZ) {
        this.transformationType = transformationType;
        this.exactTimeInTicks = exactTimeInTicks;
        this.timeInTicks = (int) Math.floor(exactTimeInTicks);
        this.interpolationType = interpolationType;
        data[0] = dataX;
        data[1] = dataY;
        data[2] = dataZ;
    }

    private void parseAxis(int axis, Object rawObject, String modelName, String animationName) {
        if (!(rawObject instanceof String rawValue)) {
            data[axis] = ((Double) rawObject).floatValue();
            return;
        }
        String source = rawValue.trim();
        if (source.isEmpty()) {
            data[axis] = transformationType == TransformationType.SCALE ? 1f : 0f;
            return;
        }
        try {
            data[axis] = (float) Double.parseDouble(source);
            return;
        } catch (NumberFormatException notANumber) {
            // Anything Blockbench accepts that is not a number is MoLang.
        }
        try {
            expressions[axis] = MolangParser.parse(source);
            sources[axis] = source;
        } catch (IllegalArgumentException malformed) {
            Logger.warn("Failed to parse the keyframe expression '" + source + "' in animation " + animationName
                    + " for model " + modelName + ": " + malformed.getMessage() + ". That value plays as 0.");
        }
    }

    public float getDataX() {
        return data[0];
    }

    public float getDataY() {
        return data[1];
    }

    public float getDataZ() {
        return data[2];
    }

    /** The constant on an axis (0 = x, 1 = y, 2 = z); 0 on an expression axis. */
    public float getData(int axis) {
        return data[axis];
    }

    /** The authored MoLang on an axis, or null where the artist typed a number. */
    public String getSource(int axis) {
        return sources[axis];
    }

    /** Whether any axis is an expression, which keeps the whole channel from being baked at load. */
    public boolean isDynamic() {
        return expressions[0] != null || expressions[1] != null || expressions[2] != null;
    }

    /**
     * The value on an axis: the constant, or the expression evaluated now. The context may be null for a
     * keyframe without expressions. A result that is not a number plays as 0 so it cannot poison the pose.
     */
    public double value(int axis, MolangContext context) {
        MolangExpression expression = expressions[axis];
        if (expression == null) return data[axis];
        context.clearTemporaries();
        double result = expression.evaluate(context);
        return Double.isFinite(result) ? result : 0;
    }
}
