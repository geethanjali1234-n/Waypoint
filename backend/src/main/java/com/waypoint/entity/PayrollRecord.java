package com.waypoint.entity;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "payroll_records")
@CompoundIndexes({
        @CompoundIndex(name = "user_payroll_period",
                def = "{'userId': 1, 'payrollPeriodStart': 1, 'payrollPeriodEnd': 1}"),
        @CompoundIndex(name = "organization_user_processedAt",
                def = "{'organizationId': 1, 'userId': 1, 'processedAt': -1}"),
        @CompoundIndex(name = "organization_manager_processedAt",
                def = "{'organizationId': 1, 'reportingManagerUserId': 1, 'processedAt': -1}")
})
public class PayrollRecord {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String userId;

    private String profileId;

    @Indexed
    private String reportingManagerUserId;

    private LocalDate payrollPeriodStart;
    private LocalDate payrollPeriodEnd;
    private List<String> timesheetIds;
    private BigDecimal totalEligibleHours;
    private BigDecimal hourlyRate;
    private BigDecimal grossPay;
    private BigDecimal federalTax;
    private BigDecimal stateTax;
    private BigDecimal localTax;
    private BigDecimal otherDeductions;
    private BigDecimal totalDeductions;
    private BigDecimal netPay;
    private String taxProvider;
    private String processedByUserId;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime processedAt;

    private LocalDateTime createdAt;
}
