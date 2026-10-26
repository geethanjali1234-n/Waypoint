package com.waypoint.service;

import com.waypoint.dto.job.JobSeekerApplicationResponse;
import com.waypoint.dto.job.JobApplicationResponse;
import com.waypoint.dto.job.JobDetailResponse;
import com.waypoint.dto.job.JobListResponse;
import com.waypoint.dto.job.JobSummaryResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobPortalServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ResumeStorageService resumeStorageService;

    @Mock
    private GeocodingService geocodingService;

    @InjectMocks
    private JobPortalService jobService;

    @BeforeEach
    void uploadedFixtureResumesAreAvailable() {
        org.mockito.Mockito.lenient().when(resumeStorageService.exists(anyString())).thenReturn(true);
    }

    private Job job(String id, String title, String location, EmploymentType employmentType, WorkMode workMode,
                     BigDecimal salaryMin, BigDecimal salaryMax, LocalDateTime createdAt) {
        return Job.builder()
                .id(id)
                .employerProfileId("employer-1")
                .jobCode("JOB-" + id)
                .title(title)
                .description("Description for " + title)
                .location(location)
                .workMode(workMode)
                .employmentType(employmentType)
                .requiredSkills(List.of("Java"))
                .salaryMin(salaryMin)
                .salaryMax(salaryMax)
                .applicationDeadline(LocalDateTime.now().plusDays(30))
                .status(JobStatus.OPEN)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Job jobWithStatus(String id, JobStatus status) {
        return Job.builder()
                .id(id)
                .employerProfileId("employer-1")
                .jobCode("JOB-" + id)
                .title("Backend Engineer")
                .description("Description for Backend Engineer")
                .location("Austin, Texas")
                .workMode(WorkMode.REMOTE)
                .employmentType(EmploymentType.FULL_TIME)
                .requiredSkills(List.of("Java"))
                .salaryMin(new BigDecimal("70000"))
                .salaryMax(new BigDecimal("90000"))
                .applicationDeadline(LocalDateTime.now().plusDays(30))
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Job openJobWithDeadline(String id, LocalDateTime applicationDeadline) {
        return Job.builder()
                .id(id)
                .employerProfileId("employer-1")
                .jobCode("JOB-" + id)
                .title("Backend Engineer")
                .description("Description for Backend Engineer")
                .location("Austin, Texas")
                .workMode(WorkMode.REMOTE)
                .employmentType(EmploymentType.FULL_TIME)
                .requiredSkills(List.of("Java"))
                .salaryMin(new BigDecimal("70000"))
                .salaryMax(new BigDecimal("90000"))
                .applicationDeadline(applicationDeadline)
                .status(JobStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private JobSeekerProfile jobSeekerProfile(String id, String userId, String resumePath) {
        return JobSeekerProfile.builder()
                .id(id)
                .userId(userId)
                .firstName("Jordan")
                .lastName("Rivera")
                .address("Austin, Texas")
                .phoneNumber("+15551234567")
                .resumePath(resumePath)
                .skills(List.of("Java"))
                .preferredJobRoles(List.of("Backend Developer"))
                .preferredLocations(List.of("Austin, Texas"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void returnsOpenJobsAndQueriesRepositoryWithOpenStatus() {
        Job openJob = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("70000"), new BigDecimal("90000"), LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(openJob));

        JobListResponse response = jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
        verify(jobRepository).findByStatus(JobStatus.OPEN);
    }

    @Test
    void excludesJobsTheJobSeekerHasAlreadyAppliedToAndKeepsTotalsAccurate() {
        Job appliedJob = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        Job openJob = job("job-2", "Frontend Engineer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "resumes/r.pdf");
        JobApplication application = JobApplication.builder()
                .id("app-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .build();

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(appliedJob, openJob));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobApplicationRepository.findByJobSeekerProfileIdAndJobIdIn(eq("jobSeeker-1"),
                argThat(jobIds -> jobIds.containsAll(List.of("job-1", "job-2")) && jobIds.size() == 2)))
                .thenReturn(List.of(application));

        JobListResponse response = jobService.getOpenJobs(
                null, null, null, null, null, null, null, null, null, null, 0, 10, "user-1");

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-2");
        assertThat(response.getContent()).extracting(JobSummaryResponse::isHasApplied).containsExactly(false);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(1);
    }

    @Test
    void filtersByTitleCaseInsensitivePartialMatch() {
        Job matching = job("job-1", "Senior Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        Job nonMatching = job("job-2", "Product Designer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(matching, nonMatching));

        JobListResponse response = jobService.getOpenJobs("BACKEND", null, null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
    }

    @Test
    void filtersByLocationCaseInsensitivePartialMatch() {
        Job matching = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        Job nonMatching = job("job-2", "Engineer", "Seattle, Washington", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(matching, nonMatching));

        JobListResponse response = jobService.getOpenJobs(null, "austin", null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
    }

    @Test
    void filtersByStateAbbreviationWhenJobUsesFullStateName() {
        Job texasJob = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        Job washingtonJob = job("job-2", "Engineer", "Seattle, Washington", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(texasJob, washingtonJob));

        JobListResponse response = jobService.getOpenJobs(null, "TX", null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
    }

    @Test
    void cityBecomesDistanceCenterAndReplacesTextFilterWhenGeocoded() {
        // Doesn't contain "Irving" in its location text, but is within range
        // of Irving's geocoded coordinates - must appear only if the text
        // filter was truly replaced, not just ANDed with distance.
        Job nearbyPlano = job("job-plano", "Engineer", "Plano, TX", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, LocalDateTime.now());
        nearbyPlano.setLatitude(new BigDecimal("33.0198"));
        nearbyPlano.setLongitude(new BigDecimal("-96.6989"));

        // Location text DOES contain "Irving", but its real coordinates are
        // far away - must still be excluded, proving distance still applies.
        Job farAwayTextMatch = job("job-far", "Engineer", "Irving Business Park, Houston, TX",
                EmploymentType.FULL_TIME, WorkMode.ONSITE, null, null, LocalDateTime.now());
        farAwayTextMatch.setLatitude(new BigDecimal("29.7604"));
        farAwayTextMatch.setLongitude(new BigDecimal("-95.3698"));

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(nearbyPlano, farAwayTextMatch));
        when(geocodingService.geocode("Irving"))
                .thenReturn(Optional.of(new GeoCoordinates(new BigDecimal("32.8140"), new BigDecimal("-96.9489"))));

        JobListResponse response = jobService.getOpenJobs(
                null, "Irving", null, null, null, null, null, null, null, 50, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-plano");
        verify(geocodingService).geocode("Irving");
    }

    @Test
    void fallsBackToTextFilterWhenCityCannotBeGeocoded() {
        Job matchingText = job("job-match", "Engineer", "Nowhereville, TX", EmploymentType.FULL_TIME,
                WorkMode.ONSITE, null, null, LocalDateTime.now());
        Job nonMatchingText = job("job-nomatch", "Engineer", "Austin, TX", EmploymentType.FULL_TIME,
                WorkMode.ONSITE, null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(matchingText, nonMatchingText));
        when(geocodingService.geocode("Nowhereville")).thenReturn(Optional.empty());

        JobListResponse response = jobService.getOpenJobs(
                null, "Nowhereville", null, null, null, null, null, null, null, 50, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-match");
    }

    @Test
    void doesNotGeocodeCityWhenNoDistanceFilterIsActive() {
        Job matching = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(matching));

        jobService.getOpenJobs(null, "austin", null, null, null, null, null, null, null, null, 0, 10, null);

        verify(geocodingService, never()).geocode(anyString());
    }

    @Test
    void usesGpsCoordinatesAndDoesNotGeocodeWhenNoCityProvided() {
        Job nearby = job("job-near", "Engineer", null, EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, LocalDateTime.now());
        nearby.setLatitude(new BigDecimal("30.2700"));
        nearby.setLongitude(new BigDecimal("-97.7400"));

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(nearby));

        JobListResponse response = jobService.getOpenJobs(
                null, null, null, null, null, null, null,
                new BigDecimal("30.2672"), new BigDecimal("-97.7431"), 10, 0, 10, null);

        assertThat(response.getContent()).extracting(JobSummaryResponse::getId).containsExactly("job-near");
        verify(geocodingService, never()).geocode(anyString());
    }

    @Test
    void ordersOnSiteJobsClosestFirstWhenCoordinatesResolvedWithoutMaxDistance() {
        LocalDateTime now = LocalDateTime.now();
        Job nearby = job("job-near", "Engineer", "Dallas, TX", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, now.minusDays(1));
        nearby.setLatitude(new BigDecimal("32.8140"));
        nearby.setLongitude(new BigDecimal("-96.9489"));

        Job faraway = job("job-far", "Engineer", "New York, NY", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, now);
        faraway.setLatitude(new BigDecimal("40.7128"));
        faraway.setLongitude(new BigDecimal("-74.0060"));

        // Default createdAt-descending sort would put the newer "far" job
        // first - proving the reorder below is driven by distance, not left
        // as date order.
        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(faraway, nearby));

        JobListResponse response = jobService.getOpenJobs(
                null, null, null, null, null, null, null,
                new BigDecimal("32.8140"), new BigDecimal("-96.9489"), null, 0, 10, null);

        assertThat(response.getContent()).extracting(JobSummaryResponse::getId)
                .containsExactly("job-near", "job-far");
    }

    @Test
    void keepsRemoteAndUncoordinatedJobsInDateOrderWhenSorting() {
        LocalDateTime now = LocalDateTime.now();
        Job remoteNewest = job("job-remote", "Engineer", "Remote", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, now);
        Job faraway = job("job-far", "Engineer", "New York, NY", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, now.minusDays(1));
        faraway.setLatitude(new BigDecimal("40.7128"));
        faraway.setLongitude(new BigDecimal("-74.0060"));
        Job nearby = job("job-near", "Engineer", "Dallas, TX", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, now.minusDays(2));
        nearby.setLatitude(new BigDecimal("32.8140"));
        nearby.setLongitude(new BigDecimal("-96.9489"));

        // Date order (createdAt descending): remote, faraway, nearby. Only the
        // two on-site jobs should reorder (nearby before faraway) - the
        // remote job must stay pinned at index 0, not pulled to either end.
        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(remoteNewest, faraway, nearby));

        JobListResponse response = jobService.getOpenJobs(
                null, null, null, null, null, null, null,
                new BigDecimal("32.8140"), new BigDecimal("-96.9489"), null, 0, 10, null);

        assertThat(response.getContent()).extracting(JobSummaryResponse::getId)
                .containsExactly("job-remote", "job-near", "job-far");
    }

    @Test
    void filtersBySalaryRangeEnum() {
        Job matching = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        matching.setSalaryRange(SalaryRange.RANGE_50K_100K);
        Job nonMatching = job("job-2", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        nonMatching.setSalaryRange(SalaryRange.RANGE_100K_150K);

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(matching, nonMatching));

        JobListResponse response = jobService.getOpenJobs(null, null, null, null, null, null,
                SalaryRange.RANGE_50K_100K, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
        assertThat(response.getContent().get(0).getSalaryRange())
                .isEqualTo(SalaryRange.RANGE_50K_100K);
    }

    @Test
    void filtersByFullStateNameWhenJobUsesStateAbbreviation() {
        Job texasJob = job("job-1", "Engineer", "Dallas, TX", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        Job washingtonJob = job("job-2", "Engineer", "Seattle, WA", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(texasJob, washingtonJob));

        JobListResponse response = jobService.getOpenJobs(null, "texas", null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
    }

    @Test
    void filtersByEmploymentType() {
        Job fullTime = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        Job contract = job("job-2", "Engineer", "Austin, Texas", EmploymentType.CONTRACT, WorkMode.REMOTE,
                null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(fullTime, contract));

        JobListResponse response =
                jobService.getOpenJobs(null, null, EmploymentType.CONTRACT, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-2");
    }

    @Test
    void filtersByWorkMode() {
        Job remote = job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, LocalDateTime.now());
        Job onsite = job("job-2", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.ONSITE,
                null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(remote, onsite));

        JobListResponse response =
                jobService.getOpenJobs(null, null, null, WorkMode.ONSITE, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-2");
    }

    @Test
    void combinesMultipleFiltersUsingAndSemantics() {
        Job matchesAll = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        Job wrongTitle = job("job-2", "Product Designer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        Job wrongLocation = job("job-3", "Backend Engineer", "Seattle, Washington", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());
        Job wrongEmploymentType = job("job-4", "Backend Engineer", "Austin, Texas", EmploymentType.CONTRACT,
                WorkMode.REMOTE, null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN))
                .thenReturn(List.of(matchesAll, wrongTitle, wrongLocation, wrongEmploymentType));

        JobListResponse response = jobService.getOpenJobs(
                "backend", "austin", EmploymentType.FULL_TIME, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1");
    }

    @Test
    void sortsByCreatedAtDescendingWithNullDatesLast() {
        LocalDateTime now = LocalDateTime.now();
        Job newest = job("job-newest", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, now);
        Job older = job("job-older", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, now.minusDays(1));
        Job noCreatedAt = job("job-null", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                null, null, null);

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(older, noCreatedAt, newest));

        JobListResponse response = jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-newest", "job-older", "job-null");
    }

    @Test
    void paginatesResultsAndReturnsCorrectMetadata() {
        LocalDateTime now = LocalDateTime.now();
        List<Job> jobs = List.of(
                job("job-1", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE, null, null, now),
                job("job-2", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE, null, null,
                        now.minusDays(1)),
                job("job-3", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE, null, null,
                        now.minusDays(2)),
                job("job-4", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE, null, null,
                        now.minusDays(3)),
                job("job-5", "Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE, null, null,
                        now.minusDays(4)));

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(jobs);

        JobListResponse response = jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, 0, 2, null);

        assertThat(response.getContent())
                .extracting(JobSummaryResponse::getId)
                .containsExactly("job-1", "job-2");
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getSize()).isEqualTo(2);
        assertThat(response.getTotalElements()).isEqualTo(5);
        assertThat(response.getTotalPages()).isEqualTo(3);
    }

    @Test
    void returnsEmptyContentWhenNoJobsMatch() {
        Job nonMatching = job("job-1", "Product Designer", "Austin, Texas", EmploymentType.FULL_TIME,
                WorkMode.REMOTE, null, null, LocalDateTime.now());

        when(jobRepository.findByStatus(JobStatus.OPEN)).thenReturn(List.of(nonMatching));

        JobListResponse response = jobService.getOpenJobs("Backend", null, null, null, null, null, null, null, null, null, 0, 10, null);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isEqualTo(0);
        assertThat(response.getTotalPages()).isEqualTo(0);
    }

    @Test
    void throwsBusinessExceptionWhenPageIsNegative() {
        assertThatThrownBy(() -> jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, -1, 10, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Page must be 0 or greater");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void throwsBusinessExceptionWhenSizeIsOutsideAllowedRange(int size) {
        assertThatThrownBy(() -> jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, 0, size, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Size must be between 1 and 50");
    }

    @ParameterizedTest
    @MethodSource("invalidSalaryRanges")
    void throwsBusinessExceptionWhenSalaryRangeIsInvalid(BigDecimal salaryMin, BigDecimal salaryMax,
                                                           String expectedMessage) {
        assertThatThrownBy(() -> jobService.getOpenJobs(null, null, null, null, salaryMin, salaryMax, null, null, null, null, 0, 10, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(expectedMessage);
    }

    private static Stream<Arguments> invalidSalaryRanges() {
        return Stream.of(
                Arguments.of(new BigDecimal("-1"), null, "Minimum salary cannot be negative"),
                Arguments.of(null, new BigDecimal("-1"), "Maximum salary cannot be negative"),
                Arguments.of(new BigDecimal("90000"), new BigDecimal("70000"),
                        "Minimum salary cannot be greater than maximum salary")
        );
    }

    @Test
    void returnsJobDetailWithCompanyNameWhenJobIsOpen() {
        Job openJob = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("70000"), new BigDecimal("90000"), LocalDateTime.now());

        EmployerProfile employerProfile = EmployerProfile.builder()
                .id("employer-1")
                .userId("user-1")
                .companyName("Waypoint Technologies")
                .companyAddress("Austin, Texas")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.of(employerProfile));

        JobDetailResponse response = jobService.getJobById("job-1", null);

        assertThat(response.getId()).isEqualTo(openJob.getId());
        assertThat(response.getJobCode()).isEqualTo(openJob.getJobCode());
        assertThat(response.getTitle()).isEqualTo(openJob.getTitle());
        assertThat(response.getDescription()).isEqualTo(openJob.getDescription());
        assertThat(response.getLocation()).isEqualTo(openJob.getLocation());
        assertThat(response.getWorkMode()).isEqualTo(openJob.getWorkMode());
        assertThat(response.getEmploymentType()).isEqualTo(openJob.getEmploymentType());
        assertThat(response.getRequiredSkills()).isEqualTo(openJob.getRequiredSkills());
        assertThat(response.getSalaryMin()).isEqualTo(openJob.getSalaryMin());
        assertThat(response.getSalaryMax()).isEqualTo(openJob.getSalaryMax());
        assertThat(response.getApplicationDeadline()).isEqualTo(openJob.getApplicationDeadline());
        assertThat(response.getStatus()).isEqualTo(openJob.getStatus());
        assertThat(response.getCreatedAt()).isEqualTo(openJob.getCreatedAt());
        assertThat(response.getCompanyName()).isEqualTo("Waypoint Technologies");

        verify(jobRepository).findById("job-1");
        verify(employerProfileRepository).findById("employer-1");
    }

    @Test
    void throwsResourceNotFoundExceptionWhenJobDoesNotExist() {
        when(jobRepository.findById("missing-job")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJobById("missing-job", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");

        verify(employerProfileRepository, never()).findById(anyString());
    }

    @Test
    void throwsResourceNotFoundExceptionWhenJobIsNotOpen() {
        Job closedJob = jobWithStatus("job-1", JobStatus.CLOSED);

        when(jobRepository.findById("job-1")).thenReturn(Optional.of(closedJob));

        assertThatThrownBy(() -> jobService.getJobById("job-1", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");

        verify(employerProfileRepository, never()).findById(anyString());
    }

    @Test
    void throwsResourceNotFoundExceptionWhenEmployerProfileIsMissing() {
        Job openJob = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("70000"), new BigDecimal("90000"), LocalDateTime.now());

        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJobById("job-1", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Employer profile not found");
    }

    @Test
    void returnsMyApplicationsMappedWithJobAndCompanyDetails() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job job = job("job-1", "Backend Engineer", "Austin, Texas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("70000"), new BigDecimal("90000"), LocalDateTime.now());
        EmployerProfile employerProfile = EmployerProfile.builder()
                .id("employer-1")
                .userId("user-employer-1")
                .companyName("Waypoint Technologies")
                .companyAddress("Austin, Texas")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        JobApplication application = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.UNDER_REVIEW)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("jobSeeker-1"))
                .thenReturn(List.of(application));
        when(jobRepository.findAllById(List.of("job-1"))).thenReturn(List.of(job));
        when(employerProfileRepository.findAllById(List.of("employer-1"))).thenReturn(List.of(employerProfile));

        List<JobSeekerApplicationResponse> response = jobService.getMyApplications("user-1");

        assertThat(response).hasSize(1);
        JobSeekerApplicationResponse result = response.get(0);
        assertThat(result.getId()).isEqualTo("application-1");
        assertThat(result.getJobId()).isEqualTo("job-1");
        assertThat(result.getJobTitle()).isEqualTo("Backend Engineer");
        assertThat(result.getCompanyName()).isEqualTo("Waypoint Technologies");
        assertThat(result.getLocation()).isEqualTo("Austin, Texas");
        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        assertThat(result.getAppliedAt()).isEqualTo(application.getAppliedAt());
    }

    @Test
    void returnsEmptyListWhenJobSeekerHasNoApplications() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("jobSeeker-1"))
                .thenReturn(List.of());
        when(jobRepository.findAllById(List.of())).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        List<JobSeekerApplicationResponse> response = jobService.getMyApplications("user-1");

        assertThat(response).isEmpty();
    }

    @Test
    void throwsResourceNotFoundExceptionWhenJobSeekerProfileDoesNotExistForMyApplications() {
        when(jobSeekerProfileRepository.findByUserId("missing-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getMyApplications("missing-user"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobSeeker profile not found");

        verify(jobApplicationRepository, never()).findByJobSeekerProfileIdOrderByAppliedAtDesc(anyString());
    }

    @Test
    void throwsResourceNotFoundExceptionWhenJobSeekerProfileDoesNotExist() {
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobSeeker profile not found");

        verify(jobRepository, never()).findById(anyString());
        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void throwsResourceNotFoundExceptionWhenJobDoesNotExistOnApply() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("missing-job")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.applyForJob("missing-job", "user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Job not found");

        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void throwsBusinessExceptionWhenJobIsNotOpen() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job closedJob = jobWithStatus("job-1", JobStatus.CLOSED);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(closedJob));

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This job is no longer accepting applications");

        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void throwsBusinessExceptionWhenApplicationDeadlineHasPassed() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job expiredDeadlineJob = openJobWithDeadline("job-1", LocalDateTime.now().minusDays(1));

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(expiredDeadlineJob));

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("The application deadline for this job has passed");

        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void allowsApplicationWhenApplicationDeadlineIsNull() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job noDeadlineJob = openJobWithDeadline("job-1", null);

        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(noDeadlineJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);

        JobApplicationResponse response = jobService.applyForJob("job-1", "user-1");

        assertThat(response.getJobId()).isEqualTo("job-1");
        assertThat(response.getJobSeekerProfileId()).isEqualTo("jobSeeker-1");
        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void throwsBusinessExceptionWhenResumePathIsMissing(String resumePath) {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", resumePath);
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Please upload a resume to your profile before applying");

        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void throwsBusinessExceptionWhenRecordedResumeFileIsMissing() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/missing.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(resumeStorageService.exists("/resumes/missing.pdf")).thenReturn(false);

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Please upload a resume to your profile before applying");

        verify(jobApplicationRepository, never()).existsByJobIdAndJobSeekerProfileId(anyString(), anyString());
        verify(jobApplicationRepository, never()).save(any());
    }

    @Test
    void throwsBusinessExceptionWhenJobSeekerAlreadyApplied() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(true);

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("You have already applied to this job");

        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appliesSuccessfullyAndSavesCorrectApplication() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);

        ArgumentCaptor<JobApplication> captor = ArgumentCaptor.forClass(JobApplication.class);

        JobApplicationResponse response = jobService.applyForJob("job-1", "user-1");

        verify(jobApplicationRepository).save(captor.capture());
        JobApplication captured = captor.getValue();

        assertThat(captured.getJobId()).isEqualTo("job-1");
        assertThat(captured.getJobSeekerProfileId()).isEqualTo("jobSeeker-1");
        assertThat(captured.getResumePath()).isEqualTo("/resumes/user-1.pdf");
        assertThat(captured.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(captured.getAppliedAt()).isNotNull();
        assertThat(captured.getUpdatedAt()).isNotNull();
        assertThat(captured.getAppliedAt()).isEqualTo(captured.getUpdatedAt());

        assertThat(response.getId()).isEqualTo(savedApplication.getId());
        assertThat(response.getJobId()).isEqualTo(savedApplication.getJobId());
        assertThat(response.getJobSeekerProfileId()).isEqualTo(savedApplication.getJobSeekerProfileId());
        assertThat(response.getStatus()).isEqualTo(savedApplication.getStatus());
        assertThat(response.getAppliedAt()).isEqualTo(savedApplication.getAppliedAt());

        verify(jobSeekerProfileRepository).findByUserId("user-1");
        verify(jobRepository).findById("job-1");
        verify(jobApplicationRepository).existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1");
    }

    @Test
    void applicationKeepsTheSharedGridFsResumePathForCrossComputerEmployerAccess() {
        String sharedResumePath = "gridfs/507f1f77bcf86cd799439011/alex.pdf";
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", sharedResumePath);
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);
        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath(sharedResumePath)
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(resumeStorageService.isSharedStorageEnabled()).thenReturn(true);
        when(resumeStorageService.exists(sharedResumePath)).thenReturn(true);
        when(resumeStorageService.isSharedStoragePath(sharedResumePath)).thenReturn(true);
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1"))
                .thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);

        JobApplicationResponse response = jobService.applyForJob("job-1", "user-1");

        ArgumentCaptor<JobApplication> applicationCaptor = ArgumentCaptor.forClass(JobApplication.class);
        verify(jobApplicationRepository).save(applicationCaptor.capture());
        assertThat(applicationCaptor.getValue().getResumePath()).isEqualTo(sharedResumePath);
        assertThat(response.getId()).isEqualTo("application-1");
    }

    @Test
    void rejectsLocalResumePathWhenSharedStorageIsConfigured() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile(
                "jobSeeker-1", "user-1", "resumes/jobSeeker-1/alex.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(resumeStorageService.isSharedStorageEnabled()).thenReturn(true);
        when(resumeStorageService.exists(jobSeekerProfile.getResumePath())).thenReturn(true);
        when(resumeStorageService.isSharedStoragePath(jobSeekerProfile.getResumePath())).thenReturn(false);

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Please upload a resume to your profile before applying");

        verify(jobApplicationRepository, never()).save(any());
    }

    @Test
    void convertsDuplicateKeyExceptionToBusinessException() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));

        assertThatThrownBy(() -> jobService.applyForJob("job-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("You have already applied to this job");

        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void appliesSuccessfullyAndNotifiesJobSeekerAndEmployer() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);
        EmployerProfile employerProfile = EmployerProfile.builder()
                .id("employer-1")
                .userId("employer-user-1")
                .companyName("Waypoint Technologies")
                .build();
        User jobSeekerUser = User.builder().id("user-1").email("jobSeeker@example.com").build();
        User employerUser = User.builder().id("employer-user-1").email("employer@example.com").build();

        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(jobSeekerUser));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.of(employerProfile));
        when(userRepository.findById("employer-user-1")).thenReturn(Optional.of(employerUser));

        jobService.applyForJob("job-1", "user-1");

        verify(notificationService, times(1)).notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Waypoint Technologies", "Jordan Rivera");
    }

    @Test
    void appliesSuccessfullyWhenEmployerProfileMissing_stillNotifiesWithNullEmployerFields() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(User.builder().id("user-1").email("jobSeeker@example.com").build()));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());

        jobService.applyForJob("job-1", "user-1");

        verify(notificationService, times(1)).notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", null, null,
                "application-1", "job-1", "Backend Engineer", null, "Jordan Rivera");
    }

    @Test
    void notificationPreparationFailure_doesNotFailApplyForJob() {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1", "/resumes/user-1.pdf");
        Job openJob = jobWithStatus("job-1", JobStatus.OPEN);

        JobApplication savedApplication = JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .resumePath("/resumes/user-1.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(openJob));
        when(jobApplicationRepository.existsByJobIdAndJobSeekerProfileId("job-1", "jobSeeker-1")).thenReturn(false);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenReturn(savedApplication);
        when(userRepository.findById("user-1")).thenThrow(new RuntimeException("boom"));

        JobApplicationResponse response = jobService.applyForJob("job-1", "user-1");

        assertThat(response.getId()).isEqualTo("application-1");
        verify(notificationService, never()).notifyApplicationSubmitted(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
