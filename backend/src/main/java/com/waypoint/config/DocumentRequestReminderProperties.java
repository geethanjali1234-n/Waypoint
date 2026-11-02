package com.waypoint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * Settings for automatic document request reminders.
 * Reminders stay off unless DOCUMENT_REQUEST_REMINDERS_ENABLED=true.
 */
@ConfigurationProperties(prefix = "waypoint.myhr.document-request-reminders")
public class DocumentRequestReminderProperties {
    private boolean enabled = false;
    private String zoneId = "America/Chicago";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZoneId() { return zoneId; }

    // Validate here so an invalid zone stops startup instead of failing at reminder time.
    public void setZoneId(String zoneId) {
        ZoneId.of(zoneId);
        this.zoneId = zoneId;
    }

    public ZoneId zone() { return ZoneId.of(zoneId); }
}
