package com.magmaguy.freeminecraftmodels.utils;

public class Round {

    private Round() {
    }

    public static double fourDecimalPlaces(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public static double twoDecimalPlaces(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

}
