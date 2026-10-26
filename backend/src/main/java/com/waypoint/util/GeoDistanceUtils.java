package com.waypoint.util;

import java.math.BigDecimal;

/**
 * Great-circle distance formula shared by Jobs and Events distance
 * filtering/sorting (both server-side, in JobPortalService/EventService).
 */
public final class GeoDistanceUtils {

    private static final double EARTH_RADIUS_MILES = 3958.8;

    private GeoDistanceUtils() {
    }

    public static double haversineMiles(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double lat1Rad = Math.toRadians(lat1.doubleValue());
        double lat2Rad = Math.toRadians(lat2.doubleValue());
        double deltaLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double deltaLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.pow(Math.sin(deltaLon / 2), 2);
        return EARTH_RADIUS_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
