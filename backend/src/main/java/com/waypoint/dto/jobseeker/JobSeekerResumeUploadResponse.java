package com.waypoint.dto.jobseeker;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class JobSeekerResumeUploadResponse {

    String fileName;
    String contentType;
    long contentLength;
}
