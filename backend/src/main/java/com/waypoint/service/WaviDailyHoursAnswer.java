package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrDailyHours;
import java.util.ArrayList;
import java.util.List;

/** Pure rendering of actual daily entries. Missing days are never inferred from a period total. */
final class WaviDailyHoursAnswer {
    private WaviDailyHoursAnswer() {}

    static String render(MyHrDailyHours data) {
        if (data == null) return "Recorded hours are temporarily unavailable. Please try again.";
        if (data.from() == null || data.to() == null) return "I don't see a timesheet of yours yet.";
        StringBuilder out = new StringBuilder("Daily recorded hours for ").append(data.from()).append(" to ").append(data.to())
                .append(". Separate submissions are not added together; these are not official payable totals. Missing entries do not mean zero hours.");
        if (data.truncated()) out.append(" Only the first 100 matching submissions are included; open Timesheets for the rest.");
        for (var day = data.from(); !day.isAfter(data.to()); day = day.plusDays(1)) {
            List<String> values = new ArrayList<>();
            for (var submission : data.submissions()) {
                for (var entry : submission.entries()) {
                    if (!day.equals(entry.day())) continue;
                    values.add((entry.hours() == null ? "hours not recorded" : entry.hours().stripTrailingZeros().toPlainString() + " hours")
                            + " (" + (submission.status() == null ? "status not recorded" : submission.status())
                            + ", period " + submission.from() + " to " + submission.to() + ")");
                }
            }
            out.append("\n- ").append(day).append(": ");
            if (values.isEmpty()) out.append("no recorded hours entry");
            else {
                out.append(String.join("; ", values.stream().limit(3).toList()));
                if (values.size() > 3) out.append("; ").append(values.size() - 3).append(" more entries on Timesheets");
            }
        }
        return out.toString();
    }
}
