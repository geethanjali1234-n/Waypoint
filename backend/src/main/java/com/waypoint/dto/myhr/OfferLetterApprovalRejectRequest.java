package com.waypoint.dto.myhr;

import jakarta.validation.constraints.Size;

public record OfferLetterApprovalRejectRequest(@Size(max = 1000) String reason) {
}
