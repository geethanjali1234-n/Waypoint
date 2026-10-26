package com.waypoint.dto.myhr;

import jakarta.validation.constraints.Size;

/**
 * Body for approve/reject decisions. processingComment is optional - the
 * existing Profile Change Requests UI never marks it required, so no
 * validation constraint is applied here either.
 */
public record ProfileChangeDecisionRequest(@Size(max = 1000) String processingComment) {
}
