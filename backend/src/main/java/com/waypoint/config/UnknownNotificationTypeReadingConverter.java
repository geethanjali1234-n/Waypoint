package com.waypoint.config;

import com.waypoint.enums.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * Keeps notification lists readable when a stored notificationType is not in this build's enum (for example a value
 * written by another branch or an older build). Known values convert exactly as before; an unrecognised value is
 * logged and read as {@link NotificationType#UNKNOWN} so one such document cannot fail the whole list.
 * Read-only: the stored value is not changed, and no converter is registered for writes.
 */
@Slf4j
@ReadingConverter
public class UnknownNotificationTypeReadingConverter implements Converter<String, NotificationType> {

    @Override
    public NotificationType convert(String source) {
        try {
            return NotificationType.valueOf(source);
        } catch (IllegalArgumentException unknownType) {
            // Only the type name is logged, never notification content. The document id is logged by the caller.
            log.warn("Unrecognised stored notification type [type={}]; reading it as UNKNOWN", source);
            return NotificationType.UNKNOWN;
        }
    }
}
