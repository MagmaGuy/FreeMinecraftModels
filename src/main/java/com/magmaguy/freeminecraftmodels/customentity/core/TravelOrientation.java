package com.magmaguy.freeminecraftmodels.customentity.core;

/**
 * How a model turns with its travel on top of facing its heading. Walkers stay level, but flying and swimming
 * creatures don't: a jellyfish leads with its bell and dives upside down, a bird pitches into climbs and dives and
 * banks into turns. The tilt grows with speed and eases back upright once the model stops.
 *
 * @param style      what leads the travel
 * @param startSpeed blocks per second below which the model stays upright
 * @param fullSpeed  blocks per second at which it lines up fully with its travel
 * @param maxTilt    the most it tilts, in degrees; 180 lets a top-first model turn upside down to dive
 * @param turnSpeed  degrees per second its tilt can change
 */
public record TravelOrientation(Style style, double startSpeed, double fullSpeed, double maxTilt, double turnSpeed) {
    /** Faces its heading and nothing more, as every model did before travel orientation existed. */
    public static final TravelOrientation UPRIGHT = new TravelOrientation(Style.UPRIGHT, 0.6D, 4D, 180D, 180D);

    public TravelOrientation {
        if (style == null) style = Style.UPRIGHT;
        if (!Double.isFinite(startSpeed) || startSpeed < 0D) startSpeed = 0D;
        if (!Double.isFinite(fullSpeed) || fullSpeed <= startSpeed) fullSpeed = startSpeed + 0.1D;
        maxTilt = Double.isFinite(maxTilt) ? Math.max(0D, Math.min(180D, maxTilt)) : 180D;
        if (!Double.isFinite(turnSpeed) || turnSpeed <= 0D) turnSpeed = 180D;
    }

    public enum Style {
        /** Only faces where it goes. */
        UPRIGHT,
        /** Its top leads, like a jellyfish: it leans into its travel and turns upside down to dive. */
        TOP_FIRST,
        /** Its front leads, like a bird or fish: it pitches into climbs and dives and banks into turns. */
        FRONT_FIRST
    }
}
