package com.waypoint.dto.employer;

import com.waypoint.enums.CompanySize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployerDashboardResponse {

    private String companyName;

    private String companyAddress;

    private String companyLogoPath;

    private String phoneNumber;

    private CompanySize companySize;

    private String email;

    private int openJobsCount;

    private int totalApplicationsCount;

    private int upcomingEventsCount;

    private int totalEventsCount;

    private int totalRegistrationsCount;

    private List<String> recentJobs;

    private List<String> recentEvents;

    private List<String> recentNotifications;
}
