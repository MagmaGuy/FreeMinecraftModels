package com.magmaguy.freeminecraftmodels.dataconverter;

import java.io.File;

/**
 * At times model makers may want to distribute models without allowing the people receiving the models to edit these models.
 * This class allows model makers to put a bbmodel file into the imports folder, where it will be stripped of all non-relevant
 * JSON formatting vis-a-vis FreeMinecraftModels, providing an end result that can be used to generate resource packs and
 * can be used to read the necessary data for skeleton hierarchy, all without giving the source editable files used in
 * the BlockBench software.
 */
public class ImportsProcessor {
    public ImportsProcessor(File file) {
    }
}
