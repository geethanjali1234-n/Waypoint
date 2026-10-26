package com.waypoint.dto.myhr;

import java.time.LocalDate;

/** The organization signature a reviewer will apply to a Signed I-983, or why none is available. */
public record I983SignatureInfoResponse(
        boolean available,
        String message,
        String signatoryName,
        String signatoryTitle,
        LocalDate effectiveDate,
        String source) {
}
