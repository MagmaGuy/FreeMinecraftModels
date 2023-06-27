package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class ChatColorConverter {

    private ChatColorConverter() {
    }

    public static String convert(String string) {
        if (string == null) return "";
        return ChatColor.translateAlternateColorCodes('&', string);
    }

    public static List<String> convert(List<String> list) {
        List<String> convertedList = new ArrayList<>();
        for (String string : list)
            convertedList.add(convert(string));
        return convertedList;
    }

}
