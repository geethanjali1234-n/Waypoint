package com.waypoint.entity;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OfferLetterVersion {
    private Integer version;
    private String documentId;
    private String status;
    private String generatedByUserId;
    private LocalDateTime generatedAt;
    private LocalDateTime sentAt;
}
