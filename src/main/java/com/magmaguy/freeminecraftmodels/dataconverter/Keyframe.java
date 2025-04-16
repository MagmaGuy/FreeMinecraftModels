package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.List;
import java.util.Map;

public class Keyframe {
    @Getter
    private final TransformationType transformationType;
    @Getter
    private final int timeInTicks;
    @Getter
    private final InterpolationType interpolationType;
    @Getter
    private final float dataX;
    @Getter
    private final float dataY;
    @Getter
    private final float dataZ;

    public Keyframe(Object object, String modelName, String animationName) {
        Map<String, Object> data = (Map<String, Object>) object;
        transformationType = TransformationType.valueOf(((String) data.get("channel")).toUpperCase());
        interpolationType = InterpolationType.valueOf(((String) data.get("interpolation")).toUpperCase());
        timeInTicks = (int) (20 * (double) data.get("time"));
        Map<String, Object> dataPoints = ((List<Map<String, Object>>) data.get("data_points")).get(0);

        dataX = tryParseFloat(dataPoints.get("x"), modelName, animationName);
        dataY = tryParseFloat(dataPoints.get("y"), modelName, animationName);
        dataZ = tryParseFloat(dataPoints.get("z"), modelName, animationName);
    }

    private float tryParseFloat(Object rawObject, String modelName, String animationName) {
        if (!(rawObject instanceof String rawValue)) return ((Double) rawObject).floatValue();
        rawValue = rawValue.replaceAll("\\n", "");
        if (rawValue.isEmpty()) return transformationType == TransformationType.SCALE ? 1f : 0f;
        try {
            return (float) Double.parseDouble(rawValue);
        } catch (Exception e) {
            Logger.warn("Failed to parse supposed number value " + rawValue + " in animation " + animationName + " for model " + modelName + "!");
            return 0;
        }
    }
}
