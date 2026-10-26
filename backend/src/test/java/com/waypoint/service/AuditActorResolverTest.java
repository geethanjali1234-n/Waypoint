package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.User;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.AuditActorResolver.ActorInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditActorResolverTest {

    @Mock UserRepository userRepository;
    @Mock EmployeeProfileRepository employeeProfileRepository;

    private AuditActorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AuditActorResolver(userRepository, employeeProfileRepository);
    }

    private User user(String id, String loginId, UserRole role) {
        return User.builder().id(id).loginId(loginId).role(role).build();
    }

    private EmployeeProfile profile(String userId, String name) {
        return EmployeeProfile.builder().userId(userId).name(name).build();
    }

    @Test
    void resolvesLoginIdRoleAndProfileNameForEachUser() {
        when(userRepository.findByIdIn(Set.of("hr-1"))).thenReturn(List.of(user("hr-1", "TD001", UserRole.HR)));
        when(employeeProfileRepository.findByUserIdIn(Set.of("hr-1"))).thenReturn(List.of(profile("hr-1", "Jane HR")));

        Map<String, ActorInfo> result = resolver.resolve(List.of("hr-1"));

        assertThat(result).containsOnlyKeys("hr-1");
        assertThat(result.get("hr-1")).isEqualTo(new ActorInfo("TD001", UserRole.HR, "Jane HR"));
    }

    @Test
    void returnsNullFieldsForAUserThatNoLongerExists() {
        when(userRepository.findByIdIn(Set.of("ghost"))).thenReturn(List.of());
        when(employeeProfileRepository.findByUserIdIn(Set.of("ghost"))).thenReturn(List.of());

        Map<String, ActorInfo> result = resolver.resolve(List.of("ghost"));

        assertThat(result.get("ghost")).isEqualTo(new ActorInfo(null, null, null));
    }

    @Test
    void omitsProfileNameWhenProfileHasNoName() {
        when(userRepository.findByIdIn(Set.of("hr-1"))).thenReturn(List.of(user("hr-1", "TD001", UserRole.HR)));
        when(employeeProfileRepository.findByUserIdIn(Set.of("hr-1"))).thenReturn(List.of(profile("hr-1", "  ")));

        Map<String, ActorInfo> result = resolver.resolve(List.of("hr-1"));

        assertThat(result.get("hr-1").name()).isNull();
        assertThat(result.get("hr-1").loginId()).isEqualTo("TD001");
    }

    @Test
    void filtersOutBlankAndNullIdsBeforeQuerying() {
        when(userRepository.findByIdIn(Set.of("hr-1"))).thenReturn(List.of(user("hr-1", "TD001", UserRole.HR)));
        when(employeeProfileRepository.findByUserIdIn(Set.of("hr-1"))).thenReturn(List.of());

        Map<String, ActorInfo> result = resolver.resolve(java.util.Arrays.asList("hr-1", "", null, "  "));

        assertThat(result).containsOnlyKeys("hr-1");
    }

    @Test
    void returnsEmptyMapWithoutQueryingWhenNoIdsGiven() {
        Map<String, ActorInfo> result = resolver.resolve(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository, employeeProfileRepository);
    }

    @Test
    void returnsEmptyMapForNullCollection() {
        Map<String, ActorInfo> result = resolver.resolve(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository, employeeProfileRepository);
    }

    @Test
    void resolveOneReturnsUnknownWhenUserIdIsNull() {
        ActorInfo result = resolver.resolveOne(null);

        assertThat(result).isEqualTo(ActorInfo.UNKNOWN);
        verifyNoInteractions(userRepository, employeeProfileRepository);
    }

    @Test
    void resolveOneReturnsUnknownWhenLookupFindsNothing() {
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of());
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());

        ActorInfo result = resolver.resolveOne("ghost");

        assertThat(result).isEqualTo(new ActorInfo(null, null, null));
    }

    @Test
    void resolveOneReturnsMatchingActor() {
        when(userRepository.findByIdIn(Set.of("hr-1"))).thenReturn(List.of(user("hr-1", "TD001", UserRole.HR)));
        when(employeeProfileRepository.findByUserIdIn(Set.of("hr-1"))).thenReturn(List.of(profile("hr-1", "Jane HR")));

        ActorInfo result = resolver.resolveOne("hr-1");

        assertThat(result).isEqualTo(new ActorInfo("TD001", UserRole.HR, "Jane HR"));
    }

    @Test
    void batchesLookupsAcrossMultipleActorsInOneCall() {
        when(userRepository.findByIdIn(Set.of("hr-1", "admin-1")))
                .thenReturn(List.of(user("hr-1", "TD001", UserRole.HR), user("admin-1", "TDADMIN", UserRole.COMPANY_ADMIN)));
        when(employeeProfileRepository.findByUserIdIn(Set.of("hr-1", "admin-1"))).thenReturn(List.of());

        resolver.resolve(List.of("hr-1", "admin-1"));

        verify(userRepository).findByIdIn(Set.of("hr-1", "admin-1"));
        verify(employeeProfileRepository).findByUserIdIn(Set.of("hr-1", "admin-1"));
    }
}
