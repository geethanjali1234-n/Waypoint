package com.waypoint.service;

import lombok.Value;

/** Plain-text label and value rendered by the shared email template. */
@Value
public class EmailDetailRow {
    String label;
    String value;
}
