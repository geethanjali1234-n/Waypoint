package com.waypoint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One immutable file in a document request's version history (the Signed
 * I-983 workflow). Every upload, replacement and signed copy gets its own
 * StoredDocument and its own entry here - nothing is overwritten or deleted,
 * so the employee's original always stays available next to the signed PDF.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRequestVersion {

    public static final String ORIGINAL = "ORIGINAL";
    public static final String REPLACEMENT = "REPLACEMENT";
    public static final String SIGNED = "SIGNED";
    public static final String EMPLOYEE_SIGNED = "EMPLOYEE_SIGNED";

    private String documentId;
    private int version;
    /**
     * ORIGINAL (uploaded by the employee), EMPLOYEE_SIGNED (the employee's upload
     * with their Profile signature applied), REPLACEMENT (uploaded by HR/Admin)
     * or SIGNED (organization signature applied).
     */
    private String kind;
    private String fileName;
    private String uploadedByUserId;
    private String uploadedByRole;
    private LocalDateTime uploadedAt;

    // SIGNED versions only: which version was signed and with what.
    private String basedOnDocumentId;
    private String signatureSource;
    private String signatoryName;
    private String signatoryTitle;
    private LocalDate signatureEffectiveDate;
    /** Pages the signature was applied to (it can be placed several times). */
    private List<Integer> signaturePages;
}
