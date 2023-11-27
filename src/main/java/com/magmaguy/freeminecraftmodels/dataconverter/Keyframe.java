package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
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
    private final double dataX;
    @Getter
    private final double dataY;
    @Getter
    private final double dataZ;

    public Keyframe(Object object, String modelName, String animationName) {
        Map<String, Object> data = (Map<String, Object>) object;
        transformationType = TransformationType.valueOf(((String) data.get("channel")).toUpperCase());
        interpolationType = InterpolationType.valueOf(((String) data.get("interpolation")).toUpperCase());
        timeInTicks = (int) (20 * (double) data.get("time"));
        Map<String, Object> dataPoints = ((List<Map<String, Object>>) data.get("data_points")).get(0);

        dataX = tryParseDouble(dataPoints.get("x"), modelName, animationName);
        dataY = tryParseDouble(dataPoints.get("y"), modelName, animationName);
        dataZ = tryParseDouble(dataPoints.get("z"), modelName, animationName);
    }

    private double tryParseDouble(Object rawObject, String modelName, String animationName) {
        if (!(rawObject instanceof String rawValue)) return (Double) rawObject;
        rawValue = rawValue.replaceAll("\\n", "");
        if (rawValue.isEmpty()) return 0;
        try {
            return Double.parseDouble(rawValue);
        } catch (Exception e) {
            Developer.warn("Failed to parse supposed number value " + rawValue + " in animation " + animationName + " for model " + modelName + "!");
            return 0;
        }
    }
}
