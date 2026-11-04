package com.waypoint.dto.myhr;

import java.time.LocalDateTime;

public record DocumentRequestDashboardSummary(long pending, LocalDateTime oldestRequestedAt) {}
