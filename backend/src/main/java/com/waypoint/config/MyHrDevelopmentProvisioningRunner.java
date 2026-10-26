package com.waypoint.config;

import com.waypoint.dto.myhr.CompanyAdminProvisioningRequest;
import com.waypoint.dto.myhr.CreatedAccountCredential;
import com.waypoint.service.CompanyAdminProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.myhr.dev-provisioning.enabled", havingValue = "true")
public class MyHrDevelopmentProvisioningRunner implements ApplicationRunner {

    private final CompanyAdminProvisioningService provisioningService;

    @Value("${waypoint.myhr.dev-provisioning.organization-id}")
    private String organizationId;

    @Value("${waypoint.myhr.dev-provisioning.admin-email}")
    private String adminEmail;

    @Override
    public void run(ApplicationArguments args) {
        CreatedAccountCredential credential = provisioningService.provision(
                new CompanyAdminProvisioningRequest(organizationId, adminEmail));
        System.out.println("MYHR_DEV_COMPANY_ADMIN_LOGIN_ID=" + credential.loginId());
        System.out.println("MYHR_DEV_COMPANY_ADMIN_TEMPORARY_PASSWORD=" + credential.temporaryPassword());
        System.out.println("MYHR_DEV_COMPANY_ADMIN_ORGANIZATION_ID=" + credential.organizationId());
    }
}
