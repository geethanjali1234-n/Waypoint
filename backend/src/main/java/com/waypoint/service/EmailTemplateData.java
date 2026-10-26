package com.waypoint.service;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Presentation values for a Waypoint transactional email. All values are
 * treated as plain text by {@link EmailTemplateService}.
 */
@Getter
@Builder
public class EmailTemplateData {

    private final String preheader;
    private final String title;
    private final String greeting;
    private final String message;
    private final String highlightedCode;
    private final List<EmailDetailRow> detailRows;
    private final String actionLabel;
    private final String actionUrl;
    private final String secondaryInformation;
    private final String footer;
}
