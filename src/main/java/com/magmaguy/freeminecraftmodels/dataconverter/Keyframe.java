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
    private double dataX;
    @Getter
    private double dataY;
    @Getter
    private double dataZ;

    public Keyframe(Object object) {
        Map<String, Object> data = (Map<String, Object>) object;
        transformationType = TransformationType.valueOf(((String) data.get("channel")).toUpperCase());
        interpolationType = InterpolationType.valueOf(((String) data.get("interpolation")).toUpperCase());
        timeInTicks = (int)(20 * (double) data.get("time"));
        Map<String, Object> dataPoints = ((List<Map<String, Object>>) data.get("data_points")).get(0);

        Object xObject = dataPoints.get("x");
        if (xObject instanceof String string)
            dataX = Double.parseDouble(string.replace("\\n", ""));
        else
            dataX = (Double) xObject;

        Object yObject = dataPoints.get("y");
        if (yObject instanceof String string)
            dataY = Double.parseDouble(string.replace("\\n", ""));
        else
            dataY = (Double) yObject;

        Object zObject = dataPoints.get("z");
        if (zObject instanceof String string)
            dataZ = Double.parseDouble(string.replace("\\n", ""));
        else
            dataZ = (Double) zObject;
    }
}
