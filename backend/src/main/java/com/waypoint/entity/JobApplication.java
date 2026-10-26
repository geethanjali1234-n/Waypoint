package com.waypoint.entity;

import com.waypoint.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "job_applications")
@CompoundIndex(name = "jobId_jobSeekerProfileId_unique", def = "{'jobId': 1, 'jobSeekerProfileId': 1}", unique = true)
public class JobApplication {

    @Id
    private String id;

    @Indexed
    private String jobId;

    @Indexed
    @Field("jobSeekerProfileId")
    private String jobSeekerProfileId;

    private String resumePath;

    @Indexed
    private ApplicationStatus status;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime appliedAt;

    private LocalDateTime updatedAt;
}
