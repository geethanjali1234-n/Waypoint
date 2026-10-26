package com.waypoint.service;

import com.waypoint.dto.auth.ChangePasswordRequest;
import com.waypoint.dto.auth.LoginRequest;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHrAuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock EmployerProfileRepository employerProfileRepository;
    @Mock JobSeekerProfileRepository jobSeekerProfileRepository;
    @Mock OtpService otpService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock DevPasswordResetOtpLogger devPasswordResetOtpLogger;
    @Mock AuditService auditService;
    @InjectMocks AuthService authService;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"COMPANY_ADMIN", "HR", "EMPLOYEE"})
    void myHrRolesLogInByLoginIdAndReceiveOrganizationAndPasswordState(UserRole role) {
        User user = activeUser(role);
        when(userRepository.findByLoginId("TD001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Temporary9!", "bcrypt-hash")).thenReturn(true);
        when(jwtService.generateToken("user-1", "user@tardus.test", role.name())).thenReturn("token");

        LoginRequest request = new LoginRequest();
        request.setLoginId("td001");
        request.setPassword("Temporary9!");
        var response = authService.login(request);

        assertThat(response.getLoginId()).isEqualTo("TD001");
        assertThat(response.getOrganizationId()).isEqualTo("org-1");
        assertThat(response.getRole()).isEqualTo(role);
        assertThat(response.getMustChangePassword()).isTrue();
        assertThat(response.getAccessToken()).isEqualTo("token");
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"JOB_SEEKER", "EMPLOYER"})
    void legacyRolesStillLogInByEmail(UserRole role) {
        User user = activeUser(role);
        user.setLoginId(null);
        user.setOrganizationId(null);
        user.setMustChangePassword(null);
        when(userRepository.findByEmail("user@tardus.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password9!", "bcrypt-hash")).thenReturn(true);
        when(jwtService.generateToken("user-1", "user@tardus.test", role.name())).thenReturn("token");

        LoginRequest request = new LoginRequest();
        request.setEmail("user@tardus.test");
        request.setPassword("Password9!");

        assertThat(authService.login(request).getRole()).isEqualTo(role);
    }

    @Test
    void blockedMyHrUserCannotLogIn() {
        User user = activeUser(UserRole.EMPLOYEE);
        user.setAccountStatus(AccountStatus.BLOCKED);
        when(userRepository.findByLoginId("TD001")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Temporary9!", "bcrypt-hash")).thenReturn(true);

        LoginRequest request = new LoginRequest();
        request.setLoginId("TD001");
        request.setPassword("Temporary9!");
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
        verify(jwtService, never()).generateToken(any(), any(), any());
    }

    @Test
    void firstLoginPasswordChangeHashesNewPasswordAndClearsFlag() {
        User user = activeUser(UserRole.HR);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Temporary9!", "bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.matches("Permanent9!", "bcrypt-hash")).thenReturn(false);
        when(passwordEncoder.encode("Permanent9!")).thenReturn("new-bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("Temporary9!");
        request.setNewPassword("Permanent9!");
        request.setConfirmPassword("Permanent9!");
        authService.changeFirstLoginPassword("user-1", request);

        assertThat(user.getPasswordHash()).isEqualTo("new-bcrypt-hash");
        assertThat(user.getMustChangePassword()).isFalse();

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        assertThat(event.getActionType()).isEqualTo(AuditActionType.PASSWORD_CHANGED);
        assertThat(event.getOrganizationId()).isEqualTo("org-1");
        assertThat(event.getActorUserId()).isEqualTo("user-1");
        assertThat(event.getUpdatedValue()).isEqualTo(java.util.Map.of("firstLogin", true));
        assertThat(String.valueOf(event.getUpdatedValue()))
                .doesNotContain("Temporary9!", "Permanent9!", "bcrypt");
    }

    private User activeUser(UserRole role) {
        return User.builder()
                .id("user-1")
                .loginId("TD001")
                .email("user@tardus.test")
                .passwordHash("bcrypt-hash")
                .role(role)
                .organizationId("org-1")
                .mustChangePassword(true)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }
}
