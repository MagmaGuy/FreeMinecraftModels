package com.magmaguy.freeminecraftmodels.utils;

import java.util.Locale;

public class StringToResourcePackFilename {
    private StringToResourcePackFilename() {
    }

    public static String convert(String original) {
        return original.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
