package com.magmaguy.freeminecraftmodels.utils;

/**
 * This class contains methods for rounding numbers.
 */
public class Round {

    /**
     * Private constructor to prevent instantiation.
     */
    private Round() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Rounds a number to a specified number of decimal places.
     * @param value The number to round.
     * @param places The number of decimal places to round to.
     * @return The rounded number.
     */
    public static double decimalPlaces(final double value, final int places) {
        final double number = Math.pow(10, places);
        return Math.round(value * number) / number;
    }

    /**
     * Rounds a number to 4 decimal places.
     * @param value The number to round.
     * @return The rounded number.
     */
    public static double fourDecimalPlaces(final double value) {
        return decimalPlaces(value, 4);
    }

    /**
     * Rounds a number to 2 decimal places.
     * @param value The number to round.
     * @return The rounded number.
     */
    public static double twoDecimalPlaces(final double value) {
        return decimalPlaces(value, 2);
    }

}
