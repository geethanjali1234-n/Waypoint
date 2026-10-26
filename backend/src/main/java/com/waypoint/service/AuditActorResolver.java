package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.User;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a user id to the display identity shown in the audit trail
 * (login id, role, full name). Batched to avoid N+1 lookups when a page of
 * audit rows references many different actors.
 */
@Component
@RequiredArgsConstructor
public class AuditActorResolver {

    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;

    public record ActorInfo(String loginId, UserRole role, String name) {
        public static final ActorInfo UNKNOWN = new ActorInfo(null, null, null);
    }

    public Map<String, ActorInfo> resolve(Collection<String> userIds) {
        Set<String> ids = userIds == null ? Set.of()
                : userIds.stream().filter(StringUtils::hasText).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<String, User> users = userRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Map<String, EmployeeProfile> profiles = employeeProfileRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (a, b) -> a));

        Map<String, ActorInfo> result = new HashMap<>();
        for (String id : ids) {
            User user = users.get(id);
            EmployeeProfile profile = profiles.get(id);
            result.put(id, new ActorInfo(
                    user == null ? null : user.getLoginId(),
                    user == null ? null : user.getRole(),
                    profile != null && StringUtils.hasText(profile.getName()) ? profile.getName() : null));
        }
        return result;
    }

    public ActorInfo resolveOne(String userId) {
        if (!StringUtils.hasText(userId)) {
            return ActorInfo.UNKNOWN;
        }
        return resolve(List.of(userId)).getOrDefault(userId, ActorInfo.UNKNOWN);
    }
}
