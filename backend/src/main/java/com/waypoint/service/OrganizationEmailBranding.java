package com.waypoint.service;

import com.waypoint.entity.Organization;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The organization details shown in employee-facing document emails.  This
 * value is derived from the tenant record, never from a value supplied by the
 * browser, so each request keeps its own organization's identity.
 */
public record OrganizationEmailBranding(
        String organizationName,
        String organizationEmail,
        String phoneNumber,
        String address,
        String website
) {
    public static OrganizationEmailBranding from(Organization organization) {
        if (organization == null) return generic();
        return new OrganizationEmailBranding(
                textOr(organization.getOrganizationName(), "Your Organization"),
                text(organization.getOrganizationEmail()),
                text(organization.getPhoneNumber()),
                address(organization.getStreetAddress(), organization.getCity(), organization.getState(), organization.getZipCode()),
                text(organization.getWebsite())
        );
    }

    public static OrganizationEmailBranding generic() {
        return new OrganizationEmailBranding("Your Organization", null, null, null, null);
    }

    public String senderDisplayName() {
        return "HR " + organizationName;
    }

    /** Two lines, as on a letterhead: "4425 W Airport Fwy STE 530" then "Irving, Texas 75062-5939". */
    private static String address(String street, String city, String state, String zipCode) {
        String stateZip = join(" ", state, zipCode);
        return join("\n", street, join(", ", city, stateZip));
    }

    private static String join(String separator, String... values) {
        String joined = Arrays.stream(values).map(OrganizationEmailBranding::text)
                .filter(StringUtils::hasText).collect(Collectors.joining(separator));
        return text(joined);
    }

    private static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized == null ? fallback : normalized;
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
