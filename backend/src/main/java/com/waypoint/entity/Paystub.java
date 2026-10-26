package com.waypoint.entity;

import com.waypoint.enums.PaystubStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "paystubs")
@CompoundIndexes({
        @CompoundIndex(name = "payrollRecord_version_unique",
                def = "{'payrollRecordId': 1, 'version': 1}", unique = true),
        @CompoundIndex(name = "organization_user_generatedAt",
                def = "{'organizationId': 1, 'userId': 1, 'generatedAt': -1}"),
        @CompoundIndex(name = "payrollRecord_status",
                def = "{'payrollRecordId': 1, 'status': 1}")
})
public class Paystub {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String userId;

    @Indexed
    private String payrollRecordId;

    @Indexed(unique = true)
    private String paystubNumber;

    private Integer version;

    @Indexed
    private PaystubStatus status;

    private String documentId;
    private String generatedByUserId;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime generatedAt;

    private LocalDateTime supersededAt;
    private LocalDateTime createdAt;
}
