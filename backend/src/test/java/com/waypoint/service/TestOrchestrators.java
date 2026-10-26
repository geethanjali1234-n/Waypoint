package com.waypoint.service;

import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds a real {@link NotificationOrchestrator} over mocked collaborators, with the frontend base URL a Spring context would inject. */
final class TestOrchestrators {

    private TestOrchestrators() { }

    static NotificationOrchestrator create(InAppNotificationService inApp, EmailService email, EmailTemplateService templates,
                                           UserRepository users, EmployeeProfileRepository profiles) {
        InAppNotificationEmailService emailDelivery = new InAppNotificationEmailService(users, profiles,
                org.mockito.Mockito.mock(OrganizationRepository.class), email, templates);
        ReflectionTestUtils.setField(emailDelivery, "frontendBaseUrl", "http://localhost:5173");
        return new NotificationOrchestrator(inApp, emailDelivery);
    }
}
