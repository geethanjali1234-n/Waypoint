package com.waypoint.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.waypoint.enums.EmploymentType.FULL_TIME;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LegacyEmploymentTypeArrayReadingConverterTest {

    @Test
    void treatsLegacyEmptyArrayAsUnspecified() {
        assertNull(new LegacyEmploymentTypeArrayReadingConverter().convert(List.of()));
    }

    @Test
    void readsSingleLegacyEnumValueWhenPresent() {
        assertSame(FULL_TIME, new LegacyEmploymentTypeArrayReadingConverter().convert(List.of("FULL_TIME")));
    }
}
