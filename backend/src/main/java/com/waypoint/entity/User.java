package com.waypoint.entity;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    @Indexed(unique = true, sparse = true)
    private String loginId;

    @Indexed
    private String organizationId;

    private String passwordHash;

    @Indexed
    private UserRole role;

    private Boolean isEmailVerified;

    @Indexed
    private AccountStatus accountStatus;

    private Boolean mustChangePassword;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
