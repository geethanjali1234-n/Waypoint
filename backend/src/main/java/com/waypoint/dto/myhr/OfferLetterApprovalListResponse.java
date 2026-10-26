package com.waypoint.dto.myhr;

import java.util.List;

/** Admin approvals page: counts plus the offer letters, newest activity first. */
public record OfferLetterApprovalListResponse(long pending, long approved, long rejected,
                                              List<OfferLetterApprovalResponse> items) {
}
