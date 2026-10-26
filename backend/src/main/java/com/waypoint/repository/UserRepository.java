package com.waypoint.repository;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    boolean existsByEmail(String email);

    boolean existsByLoginId(String loginId);

    Optional<User> findByEmail(String email);

    Optional<User> findByLoginId(String loginId);

    List<User> findByRole(UserRole role);

    List<User> findByOrganizationIdAndRoleIn(String organizationId, Collection<UserRole> roles);

    List<User> findByOrganizationIdAndAccountStatusAndRoleIn(
            String organizationId, AccountStatus accountStatus, Collection<UserRole> roles);

    List<User> findByAccountStatusAndRoleIn(AccountStatus accountStatus, Collection<UserRole> roles);

    List<User> findByIdIn(Collection<String> ids);

    List<User> findByOrganizationIdAndRoleAndAccountStatus(
            String organizationId, UserRole role, AccountStatus accountStatus);
}
