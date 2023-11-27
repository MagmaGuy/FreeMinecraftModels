package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.ChatColor;

import java.util.List;

/**
 * This class is used to convert strings to colored strings.
 */
public class ChatColorConverter {

    /**
     * Private constructor to prevent instantiation.
     */
    private ChatColorConverter() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Converts a string to a colored string.
     * @param string The string to convert.
     * @return The colored string.
     */
    public static String convert(String string) {
        if (string == null) return "";
        return ChatColor.translateAlternateColorCodes('&', string);
    }

    /**
     * Converts a list of strings to a list of colored strings.
     * @param list The list of strings to convert.
     * @return The list of colored strings.
     * @see ChatColorConverter#convert(String)
     */
    public static List<String> convert(List<String> list) {
        list.replaceAll(ChatColorConverter::convert);
        return list;
    }

}
