package com.waypoint.dto.myhr;

import jakarta.validation.constraints.Size;

/** Optional note recorded with an approval decision. */
public record OfferLetterApprovalApproveRequest(@Size(max = 1000) String comment) {
}
