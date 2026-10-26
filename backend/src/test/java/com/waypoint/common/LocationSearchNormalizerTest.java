package com.waypoint.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocationSearchNormalizerTest {

    @ParameterizedTest(name = "{0} and {1} use the same search value")
    @MethodSource("stateAliases")
    void normalizesEveryStateAbbreviationAndFullName(String abbreviation, String fullName) {
        String expected = fullName.toLowerCase(Locale.ROOT);

        assertEquals(expected, LocationSearchNormalizer.normalize(abbreviation));
        assertEquals(expected, LocationSearchNormalizer.normalize(fullName));
    }

    private static Stream<Arguments> stateAliases() {
        return Stream.of(
                Arguments.of("AL", "Alabama"),
                Arguments.of("AK", "Alaska"),
                Arguments.of("AZ", "Arizona"),
                Arguments.of("AR", "Arkansas"),
                Arguments.of("CA", "California"),
                Arguments.of("CO", "Colorado"),
                Arguments.of("CT", "Connecticut"),
                Arguments.of("DE", "Delaware"),
                Arguments.of("FL", "Florida"),
                Arguments.of("GA", "Georgia"),
                Arguments.of("HI", "Hawaii"),
                Arguments.of("ID", "Idaho"),
                Arguments.of("IL", "Illinois"),
                Arguments.of("IN", "Indiana"),
                Arguments.of("IA", "Iowa"),
                Arguments.of("KS", "Kansas"),
                Arguments.of("KY", "Kentucky"),
                Arguments.of("LA", "Louisiana"),
                Arguments.of("ME", "Maine"),
                Arguments.of("MD", "Maryland"),
                Arguments.of("MA", "Massachusetts"),
                Arguments.of("MI", "Michigan"),
                Arguments.of("MN", "Minnesota"),
                Arguments.of("MS", "Mississippi"),
                Arguments.of("MO", "Missouri"),
                Arguments.of("MT", "Montana"),
                Arguments.of("NE", "Nebraska"),
                Arguments.of("NV", "Nevada"),
                Arguments.of("NH", "New Hampshire"),
                Arguments.of("NJ", "New Jersey"),
                Arguments.of("NM", "New Mexico"),
                Arguments.of("NY", "New York"),
                Arguments.of("NC", "North Carolina"),
                Arguments.of("ND", "North Dakota"),
                Arguments.of("OH", "Ohio"),
                Arguments.of("OK", "Oklahoma"),
                Arguments.of("OR", "Oregon"),
                Arguments.of("PA", "Pennsylvania"),
                Arguments.of("RI", "Rhode Island"),
                Arguments.of("SC", "South Carolina"),
                Arguments.of("SD", "South Dakota"),
                Arguments.of("TN", "Tennessee"),
                Arguments.of("TX", "Texas"),
                Arguments.of("UT", "Utah"),
                Arguments.of("VT", "Vermont"),
                Arguments.of("VA", "Virginia"),
                Arguments.of("WA", "Washington"),
                Arguments.of("WV", "West Virginia"),
                Arguments.of("WI", "Wisconsin"),
                Arguments.of("WY", "Wyoming"),
                Arguments.of("DC", "District of Columbia"));
    }
}
