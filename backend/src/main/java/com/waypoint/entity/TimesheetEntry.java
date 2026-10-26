package com.waypoint.entity;

import com.waypoint.enums.TimesheetEntryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimesheetEntry {

    private LocalDate workDate;
    private String dayOfWeek;
    private BigDecimal hoursWorked;
    private TimesheetEntryType entryType;
    private String description;
}
