package com.waypoint.dto.myhr;

import java.time.LocalDate;

/**
 * Admin -> Organization Settings -> Authorized Signature.
 *
 * The signature image is the Company Admin's own My HR Profile signature:
 * signatureOwnerUserId/Name identify whose Profile it comes from (the admin
 * who saved these settings, or the viewing admin before anyone has) and
 * signatureConfigured says whether that Profile has a signature uploaded.
 * inEffect is the one flag signing workflows care about: a signature image
 * exists, it is marked active, and its effective date has arrived.
 */
public record AuthorizedSignatureResponse(
        String signatoryName,
        String signatoryTitle,
        LocalDate effectiveDate,
        boolean active,
        String signatureOwnerUserId,
        String signatureOwnerName,
        boolean signatureOwnerIsViewer,
        boolean signatureConfigured,
        boolean inEffect,
        String signatureUrl) {
}
