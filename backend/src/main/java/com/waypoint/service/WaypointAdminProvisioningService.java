package com.waypoint.service;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * One-time bootstrap support for the first platform administrator. This
 * service is intentionally not exposed by a controller or startup runner.
 */
@Service
@RequiredArgsConstructor
public class WaypointAdminProvisioningService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProvisioningResult provision(String loginId, String email, String temporaryPassword) {
        String normalizedLoginId = normalizeLoginId(loginId);
        String normalizedEmail = normalizeEmail(email);
        validateTemporaryPassword(temporaryPassword);

        List<User> platformAdmins = userRepository.findByRole(UserRole.WAYPOINT_ADMIN);
        if (platformAdmins.size() > 1) {
            throw new BusinessException("Multiple Waypoint Admin accounts already exist");
        }
        if (platformAdmins.size() == 1) {
            User existing = platformAdmins.getFirst();
            if (!isExactExistingAccount(existing, normalizedLoginId, normalizedEmail)) {
                throw new BusinessException("A conflicting Waypoint Admin account already exists");
            }
            return result(existing, false);
        }

        if (userRepository.existsByLoginId(normalizedLoginId)) {
            throw new BusinessException("Waypoint Admin login ID already belongs to another account");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException("Waypoint Admin email already belongs to another account");
        }

        LocalDateTime now = LocalDateTime.now();
        User account = User.builder()
                .loginId(normalizedLoginId)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(UserRole.WAYPOINT_ADMIN)
                .organizationId(null)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .mustChangePassword(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return result(userRepository.save(account), true);
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException("Waypoint Admin login ID or email was created concurrently");
        }
    }

    private boolean isExactExistingAccount(User user, String loginId, String email) {
        return loginId.equals(user.getLoginId())
                && email.equals(user.getEmail())
                && user.getRole() == UserRole.WAYPOINT_ADMIN
                && user.getOrganizationId() == null
                && user.getAccountStatus() == AccountStatus.ACTIVE
                && Boolean.TRUE.equals(user.getIsEmailVerified())
                && Boolean.TRUE.equals(user.getMustChangePassword())
                && user.getPasswordHash() != null
                && !user.getPasswordHash().isBlank();
    }

    private String normalizeLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new BusinessException("Waypoint Admin login ID is required");
        }
        return loginId.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BusinessException("A valid Waypoint Admin email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateTemporaryPassword(String temporaryPassword) {
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new BusinessException("Temporary password must be at least 8 characters");
        }
    }

    private ProvisioningResult result(User user, boolean created) {
        return new ProvisioningResult(user.getId(), user.getLoginId(), user.getEmail(), created);
    }

    public record ProvisioningResult(String userId, String loginId, String email, boolean created) {
    }
}
