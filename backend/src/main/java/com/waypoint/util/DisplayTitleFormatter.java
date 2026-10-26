package com.waypoint.util;

/** Capitalizes the first letter of each displayed word without lowercasing existing acronyms. */
public final class DisplayTitleFormatter {

    private DisplayTitleFormatter() {
    }

    public static String format(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        StringBuilder result = new StringBuilder(trimmed.length());
        boolean capitalizeNextLetter = true;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (Character.isLetter(character)) {
                result.append(capitalizeNextLetter ? Character.toUpperCase(character) : character);
                capitalizeNextLetter = false;
            } else {
                result.append(character);
                if (Character.isDigit(character)) {
                    capitalizeNextLetter = false;
                } else if (Character.isWhitespace(character) || character == '-' || character == '/') {
                    capitalizeNextLetter = true;
                }
            }
        }
        return result.toString();
    }
}
