package com.waypoint.entity;

import com.waypoint.enums.CompanySize;
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
@Document(collection = "employer_profiles")
public class EmployerProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    @Indexed
    private String companyName;

    @Indexed
    private String companyAddress;

    private String companyLogoPath;

    private String phoneNumber;

    private CompanySize companySize;

    private String companyDescription;

    private String contactPersonName;

    private String industry;

    private Integer foundedYear;

    private String careersContactEmail;

    private String website;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
