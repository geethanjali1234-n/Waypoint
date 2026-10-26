package com.waypoint.common;

import java.util.Locale;
import java.util.Map;

/**
 * Normalizes location search text so state abbreviations and full state names
 * are interchangeable (for example, "TX" and "Texas").
 */
public final class LocationSearchNormalizer {

    private static final Map<String, String> STATE_ABBREVIATIONS = Map.ofEntries(
            Map.entry("al", "alabama"),
            Map.entry("ak", "alaska"),
            Map.entry("az", "arizona"),
            Map.entry("ar", "arkansas"),
            Map.entry("ca", "california"),
            Map.entry("co", "colorado"),
            Map.entry("ct", "connecticut"),
            Map.entry("de", "delaware"),
            Map.entry("fl", "florida"),
            Map.entry("ga", "georgia"),
            Map.entry("hi", "hawaii"),
            Map.entry("id", "idaho"),
            Map.entry("il", "illinois"),
            Map.entry("in", "indiana"),
            Map.entry("ia", "iowa"),
            Map.entry("ks", "kansas"),
            Map.entry("ky", "kentucky"),
            Map.entry("la", "louisiana"),
            Map.entry("me", "maine"),
            Map.entry("md", "maryland"),
            Map.entry("ma", "massachusetts"),
            Map.entry("mi", "michigan"),
            Map.entry("mn", "minnesota"),
            Map.entry("ms", "mississippi"),
            Map.entry("mo", "missouri"),
            Map.entry("mt", "montana"),
            Map.entry("ne", "nebraska"),
            Map.entry("nv", "nevada"),
            Map.entry("nh", "new hampshire"),
            Map.entry("nj", "new jersey"),
            Map.entry("nm", "new mexico"),
            Map.entry("ny", "new york"),
            Map.entry("nc", "north carolina"),
            Map.entry("nd", "north dakota"),
            Map.entry("oh", "ohio"),
            Map.entry("ok", "oklahoma"),
            Map.entry("or", "oregon"),
            Map.entry("pa", "pennsylvania"),
            Map.entry("ri", "rhode island"),
            Map.entry("sc", "south carolina"),
            Map.entry("sd", "south dakota"),
            Map.entry("tn", "tennessee"),
            Map.entry("tx", "texas"),
            Map.entry("ut", "utah"),
            Map.entry("vt", "vermont"),
            Map.entry("va", "virginia"),
            Map.entry("wa", "washington"),
            Map.entry("wv", "west virginia"),
            Map.entry("wi", "wisconsin"),
            Map.entry("wy", "wyoming"),
            Map.entry("dc", "district of columbia"));

    private LocationSearchNormalizer() {
    }

    public static String normalize(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }

        String collapsed = location.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        StringBuilder normalized = new StringBuilder();
        for (String token : collapsed.split("\\s+")) {
            if (normalized.length() > 0) {
                normalized.append(' ');
            }
            normalized.append(STATE_ABBREVIATIONS.getOrDefault(token, token));
        }

        return normalized.toString();
    }
}
