package com.waypoint.service;

import com.waypoint.config.PayrollReadinessProperties;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PayrollReadinessAutomaticReminderService {
    private static final Logger log = LoggerFactory.getLogger(PayrollReadinessAutomaticReminderService.class);
    private static final Set<UserRole> ACTOR_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);

    private final PayrollReadinessProperties properties;
    private final PayrollReadinessService payrollReadinessService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Scheduled(cron = "${waypoint.myhr.payroll-readiness.scheduler-cron:0 15 2 * * *}", zone = "${waypoint.myhr.payroll-readiness.zone-id:America/Chicago}")
    public void runDaily() {
        runForDate(LocalDate.now(clock));
    }

    public AutomaticSchedulerResult runForDate(LocalDate runDate) {
        if (!properties.isAutomaticRemindersEnabled()) {
            return new AutomaticSchedulerResult(false, runDate, 0, 0, 0, 0);
        }
        int day = runDate.getDayOfMonth();
        boolean employeeDay = properties.getEmployeeReminderDays().contains(day) && day <= properties.getCutoffDay();
        boolean managementDay = properties.getManagementSummaryDays().contains(day) && day <= properties.getCutoffDay();
        if (!employeeDay && !managementDay) {
            return new AutomaticSchedulerResult(true, runDate, 0, 0, 0, 0);
        }

        List<User> actors = userRepository.findByAccountStatusAndRoleIn(AccountStatus.ACTIVE, ACTOR_ROLES);
        int actorCount = 0;
        int evaluated = 0;
        int sent = 0;
        int failures = 0;
        for (User user : actors) {
            if (user.getOrganizationId() == null || user.getRole() == null) continue;
            try {
                actorCount++;
                var result = payrollReadinessService.runAutomaticForActor(toActor(user), runDate);
                evaluated += result.evaluated();
                sent += result.sent();
                failures += result.failed();
            } catch (RuntimeException exception) {
                failures++;
                log.warn("Payroll readiness automatic reminder run failed for actor {}", user.getId(), exception);
            }
        }
        return new AutomaticSchedulerResult(true, runDate, actorCount, evaluated, sent, failures);
    }

    private AuthenticatedMyHrUser toActor(User user) {
        return new AuthenticatedMyHrUser(
                user.getId(),
                user.getLoginId(),
                user.getEmail(),
                user.getRole(),
                user.getOrganizationId(),
                user.getAccountStatus(),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    public record AutomaticSchedulerResult(boolean enabled, LocalDate runDate, int actorsProcessed, int employeesEvaluated, int employeeNotificationsSent, int failures) {
    }
}
