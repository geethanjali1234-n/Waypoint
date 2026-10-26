package com.waypoint.service;

import com.waypoint.dto.profile.EmployerProfileResponse;
import com.waypoint.dto.profile.EmployerProfileUpdateRequest;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.User;
import com.waypoint.enums.CompanySize;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployerProfileServiceTest {

    private static final String USER_ID = "employer-user-1";
    private static final String PROFILE_ID = "employer-profile-1";

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private EmployerProfileService employerProfileService;

    @Test
    void getProfileReturnsMappedProfileAndCompletionPercentage() {
        User user = user();
        EmployerProfile profile = EmployerProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .companyName("Waypoint Labs")
                .companyDescription("Career technology")
                .contactPersonName("Alex Morgan")
                .industry("Technology")
                .companyAddress("123 Main Street")
                .phoneNumber("+13125550123")
                .companySize(CompanySize.MEDIUM)
                .foundedYear(2020)
                .careersContactEmail("careers@waypoint.test")
                .website("https://waypoint.test")
                .companyLogoPath("company-logos/logo.png")
                .build();
        stubProfile(user, profile);

        EmployerProfileResponse response = employerProfileService.getProfile(USER_ID);

        assertEquals(PROFILE_ID, response.getEmployerProfileId());
        assertEquals(USER_ID, response.getUserId());
        assertEquals(user.getEmail(), response.getEmail());
        assertEquals("/uploads/company-logos/logo.png", response.getCompanyLogoUrl());
        assertEquals(100, response.getCompletionPercentage());
    }

    @Test
    void updateProfileNormalizesValuesAndRemovesExistingLogo() {
        EmployerProfile profile = EmployerProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .companyLogoPath("company-logos/old-logo.png")
                .build();
        EmployerProfileUpdateRequest request = new EmployerProfileUpdateRequest();
        request.setCompanyName("  Acme Corporation  ");
        request.setCompanyDescription("   ");
        request.setContactPersonName("  Taylor Smith  ");
        request.setIndustry("  Finance  ");
        request.setCompanyAddress("  500 Market Street  ");
        request.setPhoneNumber("+13125550123");
        request.setCompanySize(CompanySize.LARGE);
        request.setFoundedYear(2015);
        request.setCareersContactEmail("  CAREERS@ACME.TEST  ");
        request.setWebsite("  https://acme.test  ");
        request.setRemoveCompanyLogo(true);
        stubProfile(user(), profile);
        when(employerProfileRepository.save(profile)).thenReturn(profile);

        EmployerProfileResponse response = employerProfileService.updateProfile(USER_ID, request);

        assertEquals("Acme Corporation", profile.getCompanyName());
        assertNull(profile.getCompanyDescription());
        assertEquals("Taylor Smith", profile.getContactPersonName());
        assertEquals("careers@acme.test", profile.getCareersContactEmail());
        assertEquals("https://acme.test", profile.getWebsite());
        assertNull(profile.getCompanyLogoPath());
        assertNull(response.getCompanyLogoUrl());
        verify(fileStorageService).remove("company-logos", PROFILE_ID);
        verify(employerProfileRepository).save(profile);
    }

    @Test
    void updateLogoStoresFileAndReturnsItsPublicUrl() {
        EmployerProfile profile = EmployerProfile.builder().id(PROFILE_ID).userId(USER_ID).build();
        MockMultipartFile logo = new MockMultipartFile(
                "file", "logo.png", "image/png", "image-data".getBytes());
        stubProfile(user(), profile);
        when(fileStorageService.store(logo, "company-logos", PROFILE_ID))
                .thenReturn("company-logos/stored-logo.png");
        when(employerProfileRepository.save(profile)).thenReturn(profile);

        EmployerProfileResponse response = employerProfileService.updateLogo(USER_ID, logo);

        assertEquals("company-logos/stored-logo.png", profile.getCompanyLogoPath());
        assertEquals("/uploads/company-logos/stored-logo.png", response.getCompanyLogoUrl());
        verify(employerProfileRepository).save(profile);
    }

    @Test
    void getProfileThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> employerProfileService.getProfile(USER_ID));

        verify(employerProfileRepository, never()).findByUserId(USER_ID);
    }

    @Test
    void getProfileThrowsWhenEmployerProfileDoesNotExist() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(employerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> employerProfileService.getProfile(USER_ID));
    }

    private User user() {
        return User.builder().id(USER_ID).email("employer@waypoint.test").build();
    }

    private void stubProfile(User user, EmployerProfile profile) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(employerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
    }
}
