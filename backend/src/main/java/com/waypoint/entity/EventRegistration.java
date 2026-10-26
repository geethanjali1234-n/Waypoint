package com.waypoint.entity;

import com.waypoint.enums.AttendanceStatus;
import com.waypoint.enums.RegistrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "event_registrations")
@CompoundIndex(name = "eventId_jobSeekerProfileId_unique", def = "{'eventId': 1, 'jobSeekerProfileId': 1}", unique = true)
public class EventRegistration {

    @Id
    private String id;

    @Indexed
    private String eventId;

    @Indexed
    @Field("jobSeekerProfileId")
    private String jobSeekerProfileId;

    @Indexed
    private RegistrationStatus registrationStatus;

    private AttendanceStatus attendanceStatus;

    private LocalDateTime registeredAt;

    private LocalDateTime updatedAt;
}
