package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Organization-level authorization to send the results of Wavi's record tools (a user's own timesheets, profile,
 * requests, and the queues and people a reviewer may see) to an external AI provider so it can word the answer.
 *
 * <p>This is separate from {@link ExternalPolicyProcessingAuthorization}, which only covers approved company-policy
 * excerpts: permission to send a policy excerpt is not permission to send an employee's records. Until a Company
 * Admin's decision is recorded for an organization (here: its id listed in
 * {@code waypoint.myhr.agent.approved-organization-ids}), nothing is permitted and the Claude tool-use agent does
 * not run for that organization at all. Never read from a request.
 */
@FunctionalInterface
public interface ExternalRecordProcessingAuthorization {

    /** Permits nothing; the default wherever no organization decision exists. */
    ExternalRecordProcessingAuthorization DENY_ALL = (organizationId, role) -> false;

    boolean permits(String organizationId, UserRole role);

    /**
     * Whether person names may ALSO be sent (a manager's name, who is waiting for review). A separate, stricter
     * decision: authorizing work records does not authorize naming employees to the provider. Denied by default.
     */
    default boolean permitsPersonNames(String organizationId, UserRole role) {
        return false;
    }

    /** The production implementation: an explicit allow-list of organization ids, empty (deny all) by default. */
    @Component
    final class Configured implements ExternalRecordProcessingAuthorization {
        private final Set<String> approved;
        private final Set<String> approvedForNames;

        @org.springframework.beans.factory.annotation.Autowired
        Configured(@Value("${waypoint.myhr.agent.approved-organization-ids:}") String organizationIds,
                   @Value("${waypoint.myhr.agent.approved-person-name-organization-ids:}") String nameOrganizationIds) {
            this.approved = ids(organizationIds);
            this.approvedForNames = ids(nameOrganizationIds);
        }

        Configured(String organizationIds) {
            this(organizationIds, "");
        }

        private static Set<String> ids(String list) {
            return list == null ? Set.of() : Arrays.stream(list.split(","))
                    .map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public boolean permitsPersonNames(String organizationId, UserRole role) {
            return permits(organizationId, role) && approvedForNames.contains(organizationId);
        }

        @Override
        public boolean permits(String organizationId, UserRole role) {
            return organizationId != null && role != null && approved.contains(organizationId);
        }
    }
}
