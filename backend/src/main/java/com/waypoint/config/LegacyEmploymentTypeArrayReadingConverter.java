package com.waypoint.config;

import com.waypoint.enums.EmploymentType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

import java.util.Collection;
import java.util.Locale;

/**
 * Keeps older jobSeeker profile documents readable when a scalar employment
 * type was previously stored as an empty array.
 */
@ReadingConverter
public class LegacyEmploymentTypeArrayReadingConverter
        implements Converter<Collection<?>, EmploymentType> {

    @Override
    public EmploymentType convert(Collection<?> source) {
        if (source == null || source.size() != 1) {
            return null;
        }

        Object value = source.iterator().next();
        if (value instanceof EmploymentType employmentType) {
            return employmentType;
        }

        if (value instanceof String text) {
            try {
                return EmploymentType.valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        return null;
    }
}
