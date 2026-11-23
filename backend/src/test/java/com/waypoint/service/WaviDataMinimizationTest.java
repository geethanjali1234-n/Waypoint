package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OFFLINE: what the central disclosure policy and the scrubber let through, with synthetic values only. */
class WaviDataMinimizationTest {

    @Test
    void contactDetailsIdentifiersLinksAndSecretsAreRemoved() {
        String scrubbed = WaviDataMinimizer.minimize("Contact asha.rao@example.test or +1 (555) 010-2030, code WPT-1001, "
                + "record 64b7f0c2a1d3e4f5a6b7c8d9, see https://example.test/x?token=abc, password: hunter2");
        assertThat(scrubbed).doesNotContain("asha.rao", "555", "WPT-1001", "64b7f0c2a1d3e4f5a6b7c8d9", "example.test",
                "hunter2");
        assertThat(scrubbed).contains(WaviDataMinimizer.EMAIL, WaviDataMinimizer.PHONE, WaviDataMinimizer.IDENTIFIER,
                WaviDataMinimizer.LINK);
    }

    @Test
    void datesHoursAndCountsSurviveBecauseAnswersNeedThem() {
        String text = "Mon Oct 12, 2026 to Sun Oct 18, 2026 (2026-10-12 2026-10-18): 40 hours, 3 timesheets, submitted 2026-10-14 17:00";
        assertThat(WaviDataMinimizer.minimize(text)).isEqualTo(text);
    }

    @Test
    void minimizingIsIdempotentAndNullSafe() {
        String once = WaviDataMinimizer.minimize("mail me at a@b.test");
        assertThat(WaviDataMinimizer.minimize(once)).isEqualTo(once);
        assertThat(WaviDataMinimizer.minimize(null)).isEmpty();
    }

    @Test
    void identifiersAndContactFieldsAreNeverSendableWhateverIsAuthorized() {
        ExternalRecordProcessingAuthorization everything = new ExternalRecordProcessingAuthorization() {
            @Override
            public boolean permits(String organization, UserRole role) {
                return true;
            }

            @Override
            public boolean permitsPersonNames(String organization, UserRole role) {
                return true;
            }
        };
        for (String field : new String[] {"EMPLOYEE_ID", "WORK_EMAIL", "PROFILE"}) {
            var level = WaviExternalDisclosure.classify(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", field), UserRole.EMPLOYEE);
            assertThat(WaviExternalDisclosure.sendable(level, "org", UserRole.EMPLOYEE, everything)).as(field).isFalse();
        }
        for (String field : new String[] {"EMPLOYEE_ID", "WORK_EMAIL", "SUMMARY"}) {
            var level = WaviExternalDisclosure.classify(WaviTools.GET_TEAM_MEMBER, Map.of("field", field), UserRole.HR);
            assertThat(WaviExternalDisclosure.sendable(level, "org", UserRole.HR, everything)).as(field).isFalse();
        }
    }

    @Test
    void namesNeedTheirOwnApprovalAndRecordsNeedTheOrganizationsApproval() {
        ExternalRecordProcessingAuthorization recordsOnly = (organization, role) -> true;
        var named = WaviExternalDisclosure.classify(WaviTools.GET_REVIEW_QUEUE, Map.of("detail", "LIST"), UserRole.HR);
        var counts = WaviExternalDisclosure.classify(WaviTools.GET_REVIEW_QUEUE, Map.of("detail", "COUNT"), UserRole.HR);
        assertThat(named).isEqualTo(WaviExternalDisclosure.Level.NAMED);
        assertThat(WaviExternalDisclosure.sendable(named, "org", UserRole.HR, recordsOnly)).isFalse();
        assertThat(WaviExternalDisclosure.sendable(counts, "org", UserRole.HR, recordsOnly)).isTrue();
        assertThat(WaviExternalDisclosure.sendable(counts, "org", UserRole.HR, ExternalRecordProcessingAuthorization.DENY_ALL))
                .isFalse();
        // Fixed, role-wide Waypoint text needs no record approval.
        assertThat(WaviExternalDisclosure.sendable(WaviExternalDisclosure.Level.PUBLIC, "org", UserRole.HR,
                ExternalRecordProcessingAuthorization.DENY_ALL)).isTrue();
    }

    @Test
    void theProductionNameApprovalIsSeparateAndRequiresTheRecordApprovalToo() {
        var configured = new ExternalRecordProcessingAuthorization.Configured("org-1,org-2", "org-2,org-3");
        assertThat(configured.permitsPersonNames("org-1", UserRole.HR)).isFalse();
        assertThat(configured.permitsPersonNames("org-2", UserRole.HR)).isTrue();
        // Named for names only, never approved for records: nothing is permitted.
        assertThat(configured.permitsPersonNames("org-3", UserRole.HR)).isFalse();
        assertThat(new ExternalRecordProcessingAuthorization.Configured("").permitsPersonNames("org-1", UserRole.HR)).isFalse();
    }
}
