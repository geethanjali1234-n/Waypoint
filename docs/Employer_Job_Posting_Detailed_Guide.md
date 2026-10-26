# Waypoint | Employer Job Posting

![Waypoint mark](assets/waypoint-mark.svg)

## A practical guide to creating, publishing, and managing roles.

This guide explains the Employer Job Posting feature in Waypoint in clear
business language and technical detail. It is designed to help a new developer
understand the implementation and help an employer, student, or presenter
explain the workflow with confidence.

It follows one question from beginning to end: what happens after an employer
selects New posting? The answer crosses the React interface, Axios request
layer, Spring Boot API, JWT security boundary, business services, MongoDB
document, job seeker job search, applications, and applicant review.

The guide reflects the implementation in the
feature/rename-employee-to-job-seeker branch. The source files used to
prepare it are listed at the end.

> The decision path: the browser gathers input, the API authenticates it, the
> service enforces the rules, and MongoDB preserves the result.

![Waypoint route motif](assets/waypoint-route.svg)

---

## How this guide maps to the requirement brief

The accompanying requirement note asks for more than a description of the form. It
asks for a complete feature explanation that can be used for development,
demonstration, manual testing, and follow-up discussion. This guide covers the
requested areas as follows:

| Requirement from the brief | Where to study it in this guide |
|---|---|
| Overall workflow and flow chart | Sections 1, 6, and 18 |
| API endpoints | Sections 9, 10, and the downstream endpoint catalog |
| Entity relationships for this flow | Section 12 |
| Business rules and validation | Sections 7, 11, and the business-rule matrix |
| Manual testing test cases | Section 16 |
| Brief explanation for presentation | Sections 18 and 19 |
| Follow-up questions and answers | Section 20 |
| Notification service and email service concepts | Section 14 |
| Future enhancements | Section 21 |

Read the guide in two passes. First, follow the employer's clicks and the
job seeker's application journey. Second, trace the same journey through the
frontend component, HTTP endpoint, security interceptor, service, repository,
MongoDB document, and notification channels.

### Scope note: implemented behavior versus product ideas

The guide describes the code that exists on the
`feature/rename-employee-to-job-seeker` branch. A few product ideas are
visible in the model or enum but are not complete workflows yet:

- New postings are published directly as `OPEN`; the UI does not have a separate
  save-as-draft action.
- `EXPIRED` exists as a status, but no scheduled process automatically changes an
  open job to `EXPIRED` after its deadline.
- `resumeRequired` is stored on the job, but the current application service still
  requires a usable resume for every application.
- The notification system supports application events, while creating a job does
  not currently broadcast a `NEW_JOB_POSTED` event.

These boundaries are important in a presentation because they show the difference
between what the current feature guarantees and what can be added next.

---

## 1. Feature at a glance

The Employer Job Posting feature gives an authenticated employer one controlled
place to create, publish, review, edit, close, and reopen job postings.

The end-to-end flow is:

1. The employer logs in.
2. The frontend stores the access token in localStorage.
3. The employer opens /employer/jobs.
4. React loads the employer's own jobs from the backend.
5. The employer selects New posting.
6. The frontend requests a unique Job ID and displays the posting form.
7. The employer enters the title, work type, location, employment type,
   experience level, required skills, salary range, description, deadline, and
   resume requirement.
8. React performs immediate validation for required fields and date rules.
9. Axios sends JSON to POST /api/employer/jobs.
10. The backend verifies the JWT and confirms the token represents an employer.
11. The service verifies that the employer profile is complete, validates
    business rules, sets the owner and status, and saves the job in MongoDB.
12. The backend returns the saved job.
13. React updates the list and returns the employer to the Job Postings page.
14. Because a new posting starts as OPEN, job seekers can see it in Careers and
    apply while the deadline has not passed.
15. The employer can open the applicant list, inspect applicants, update
    application statuses, and preview or download resumes.

The most important architectural idea is separation of responsibility. Each
layer has a clear job:

- React owns the user interface and fast feedback.
- Axios owns HTTP communication.
- The controller owns HTTP endpoint handling.
- The interceptor owns authentication and role checks.
- The service owns business rules and ownership checks.
- The repository owns MongoDB access.
- The Job entity represents the stored document.

---

## 2. Why this feature exists

An employer needs a reliable way to announce an open role and receive
applications from job seekers. The feature solves four related problems:

- It gives employers a structured form instead of unstructured text.
- It makes job information searchable and filterable for job seekers.
- It connects a posting to the employer who owns it.
- It connects the posting to the later applicant-review workflow.

The posting is the starting point of a larger workflow:

~~~text
Employer profile -> Job posting -> Job seeker job search -> Application
-> Applicant review -> Hiring decision
~~~

---

## 3. People and systems involved

### Employer

The employer creates and manages postings. The employer must be authenticated
and must have the EMPLOYER role.

### Job seeker

The job seeker sees open jobs in Careers, views job details, applies, and later
tracks the application status.

### Frontend

The frontend is a React application served by Vite. The main page is
frontend/src/pages/JobPostingPage.jsx.

### Backend

The backend is a Spring Boot application. The main employer job controller is
backend/src/main/java/com/waypoint/controller/JobController.java.

### Database

MongoDB stores job documents. Related documents are stored in
employer_profiles, users, job_applications, and job seeker profile collections.

### Authentication

The backend uses a signed JWT. The token includes the authenticated user ID and
role. The frontend sends it as:

~~~text
Authorization: Bearer <access-token>
~~~

---

## 4. Before an employer can publish

The following conditions must be true:

1. The employer has an account.
2. The employer has logged in successfully.
3. localStorage.accessToken contains a valid token.
4. The token has the EMPLOYER role.
5. The employer has an EmployerProfile record.
6. The profile contains companyName, companyAddress, phoneNumber, and
   companySize.
7. The frontend can reach the backend.
8. The backend can reach MongoDB.

The service checks the employer profile before saving a job. This prevents a
posting from being created without enough company information for job seekers to
understand who is hiring.

If the profile is incomplete, the backend returns:

~~~text
Complete the required employer profile information before creating a job posting
~~~

The profile check happens on the server, so it cannot be bypassed by manually
calling the API.

---

## 5. Frontend entry points

### Route map and responsibilities

| Route | Purpose |
|---|---|
| /employer/dashboard | Employer home page with hiring summary |
| /employer/jobs | List, search, filter, edit, close, and reopen postings |
| /employer/jobs/new | Open the new posting dialog |
| /employer/jobs/:jobId/applicants | View applicants for one posting |
| /employer/jobs/:jobId/applicants/:applicationId | Review one applicant |
| /careers | Job seeker-facing list of open jobs |
| /careers/:jobId | Job seeker-facing job details |
| /careers/:jobId/apply | Job seeker-facing application page |

These routes are registered in frontend/src/routes/AppRouter.jsx.

The /employer routes are nested under EmployerLayout. The layout provides the
persistent employer header and navigation. The navigation marks Job Postings as
active whenever the pathname starts with /employer/jobs.

### Role protection

RoleProtectedRoute redirects users without an access token to /login. That is a
user-experience guard only. It is not the real security boundary.

The real security boundary is the backend JwtAuthInterceptor. The backend
checks the token on every protected employer API request and rejects a token
that is missing, invalid, expired, or associated with a different role.

This is an important explanation point:

> Hiding a page in React does not secure data. The server must authorize every
> protected request.

---

## 6. The employer workflow, step by step

### Overall workflow flow chart

The primary success path can be explained as one connected route. The browser
starts the interaction, but the server remains responsible for identity,
ownership, validation, and persistence.

~~~text
Employer logs in
      |
      v
/employer/jobs
      |
      | GET /api/employer/jobs + employer JWT
      v
JwtAuthInterceptor
      |-- 401: token missing, invalid, or expired
      |-- 403: token is not an employer token
      v
JobController -> JobService -> EmployerProfile + JobRepository
      |
      | employer selects New posting
      v
/employer/jobs/new
      |
      | GET /api/employer/jobs/new-code
      v
New job dialog
      |
      | client validation: required values and deadline
      v
POST /api/employer/jobs
      |
      | bean validation + profile validation + business rules
      |-- 400: invalid fields, salary order, deadline, or skills
      |-- 404: employer profile not found
      v
MongoDB: jobs collection
      |
      | saved with owner, unique Job ID, OPEN status, and timestamps
      v
201 response -> employer list refresh -> job appears in Active jobs
      |
      v
Job seeker Careers -> job details -> application
      |
      | OPEN status + deadline + resume + duplicate checks
      v
JobApplication -> applicant review -> status updates -> notifications
~~~

The alternate management path branches from the employer list:

~~~text
OPEN job  ->  Edit  ->  PUT /api/employer/jobs/{jobId}
OPEN job  ->  Close ->  PATCH /api/employer/jobs/{jobId}/close -> CLOSED
CLOSED job -> Reopen -> PATCH /api/employer/jobs/{jobId}/reopen -> OPEN
~~~

The important learning point is that the frontend may hide a button, but the
backend repeats the status and ownership checks. A direct API caller must still
pass the same rules.

### Step 1: Open Job Postings

The employer selects Job Postings from the employer header.

React renders JobPostingPage at:

~~~text
/employer/jobs
~~~

When the page mounts, it calls:

~~~text
GET /api/employer/jobs
~~~

The response is placed into the local jobs state. While the request is pending,
the table displays Loading job postings.... If the request fails, the page
displays the backend message or the fallback message Unable to load job
postings.

### Step 2: Review the job table

Each row displays:

- Job title
- Posted date
- Work mode
- Location
- Employment type
- Salary display
- Number of applications
- Status
- Actions

The title is a button. Selecting it navigates to:

~~~text
/employer/jobs/{jobId}/applicants
~~~

The page also provides:

- Search by title, Job ID, work mode, employment type, experience level,
  location, or required skill.
- Status filtering for active (OPEN) and closed (CLOSED) jobs.
- An empty state when no jobs match.

The list is filtered in memory with useMemo. This keeps the page responsive
after the initial list request and avoids a request for every keystroke.

### Page anatomy and visible controls

The employer page is easier to explain when it is divided into four visual areas:

| Page area | What the employer sees | What it does | Main implementation idea |
|---|---|---|---|
| Page heading | Job postings title, ownership message, and New posting button | Starts the create flow | Route navigation to `/employer/jobs/new` |
| Toolbar | Active and closed jobs title, search input, and Status select | Narrows the already-loaded list | `search`, `statusFilter`, and `useMemo` |
| Job table | Job title, posting metadata, type and salary, application count, status, and actions | Gives a quick operational view | `filteredJobs.map(...)` renders one row per job |
| Posting dialog | Job ID, job fields, validation messages, Cancel, and Publish or Save changes | Creates or edits one posting | Controlled form state plus `saveJob()` |

The title in each row is also a navigation control. It opens the applicants page
for that job, which makes the posting page the beginning of the employer's
recruiting workflow rather than an isolated CRUD screen.

### Step 3: Select New posting

Selecting + New posting navigates to:

~~~text
/employer/jobs/new
~~~

The same JobPostingPage component is reused. It determines that it is in
create mode because the pathname ends with /new.

When create mode opens and no Job ID exists yet, React calls:

~~~text
GET /api/employer/jobs/new-code
~~~

The backend generates a value such as:

~~~text
JOB-4A9F2C10
~~~

The Job ID is displayed but disabled. The employer does not type it manually.

### Step 4: Complete the form

The form is a modal dialog. It includes the fields described in the next
section. Required fields are marked in the UI. Required skills use the shared
MultiSelect component.

The form starts with this conceptual state:

~~~text
jobCode: ""
title: ""
workMode: ""
location: ""
employmentType: ""
experienceLevel: ""
requiredSkills: []
salaryRange: ""
salaryMin: ""
salaryMax: ""
description: ""
applicationDeadline: ""
resumeRequired: "true"
~~~

The frontend stores the resume choice as a string because it is controlled by a
HTML select. Before sending the request it converts the value to a boolean.

### Step 5: Client-side validation

Before the API call, validate() checks:

- Job title is present.
- Work type is selected.
- Location is selected.
- Employment type is selected.
- Experience level is selected.
- At least one required skill is selected.
- Description is present.
- Application deadline is present.
- Application deadline is not before today's local date.

If validation fails:

1. The API is not called.
2. fieldErrors receives field-specific messages.
3. Each field displays its error below the control.
4. The user can correct the field without losing the other form values.

This is client-side validation. It improves the user experience, but it does
not replace backend validation.

### Step 6: Build the JSON payload

After validation, saveJob() trims text fields and converts values into the
format expected by Java and MongoDB.

Example payload:

~~~json
{
  "jobCode": "JOB-4A9F2C10",
  "title": "Senior React Engineer",
  "workMode": "HYBRID",
  "location": "Austin, Texas",
  "employmentType": "FULL_TIME",
  "experienceLevel": "SENIOR_LEVEL",
  "requiredSkills": ["React", "JavaScript", "REST APIs"],
  "salaryRange": "RANGE_100K_150K",
  "salaryMin": null,
  "salaryMax": null,
  "description": "Build accessible product experiences for our platform team.",
  "applicationDeadline": "2026-12-31",
  "resumeRequired": true
}
~~~

When a salary range is selected, the current frontend sends the range and sets
the older numeric salary fields to null. If an older job uses numeric salary
values without a salary range, the frontend preserves those numeric values.

### Step 7: Publish

For a new job, the page calls:

~~~text
POST /api/employer/jobs
~~~

For an existing open job, the page calls:

~~~text
PUT /api/employer/jobs/{jobId}
~~~

The button displays a loading state while the request is in progress. This
prevents accidental duplicate submissions from repeated clicks.

### Step 8: Handle the response

The backend returns the saved job inside the standard ApiResponse wrapper. The
page:

1. Adds or replaces the returned job in jobs.
2. Resets the form.
3. Clears edit mode.
4. Shows a success message.
5. Navigates back to /employer/jobs.

Example success message:

~~~text
"Senior React Engineer" was created successfully.
~~~

### Step 9: Manage a posting

For an OPEN job:

- Edit is enabled.
- Close is available.

For a CLOSED job:

- Edit is disabled.
- Reopen is available.

The frontend chooses the action from the current status. The backend verifies
the expected status again, so a user cannot bypass the lifecycle rule by
calling the endpoint directly.

---

## 7. Job form: field reference

| UI field | JSON field | Type | Required | Allowed values or rule | Why it matters |
|---|---|---|---|---|---|
| Job ID | jobCode | String | Generated | JOB- followed by 8 uppercase hexadecimal characters | Human-readable unique identifier |
| Job title | title | String | Yes | Maximum 150 characters | Main role name shown to job seekers |
| Work type | workMode | Enum | Yes | ONSITE, REMOTE, HYBRID | Describes how the role is performed |
| Location | location | String | Yes | Selected city and state, maximum 150 characters | Supports job seeker location search |
| Employment type | employmentType | Enum | Yes | FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP | Describes the employment relationship |
| Experience level | experienceLevel | Enum | Yes | ENTRY_LEVEL, MID_LEVEL, SENIOR_LEVEL, LEAD, EXECUTIVE | Helps job seekers understand seniority |
| Required skills | requiredSkills | List of strings | Yes, at least one | Each skill must be nonblank | Drives the skill-match presentation |
| Salary range | salaryRange | Enum | No | RANGE_10K_50K through RANGE_950K_1000K | Gives job seekers a consistent salary band |
| Old salary minimum | salaryMin | Decimal | No | Must be zero or greater | Backward compatibility for older postings |
| Old salary maximum | salaryMax | Decimal | No | Must be zero or greater and not below minimum | Backward compatibility for older postings |
| Description | description | String | Yes | Maximum 5,000 characters | Explains responsibilities and expectations |
| Application deadline | applicationDeadline | Date | Yes | Today or a future date | Controls whether applications can be submitted |
| Resume required | resumeRequired | Boolean | Yes | true or false | Stores the employer's resume preference |

### Work type values

| Display label | Backend value |
|---|---|
| On-site | ONSITE |
| Remote | REMOTE |
| Hybrid | HYBRID |

### Employment type values

| Display label | Backend value |
|---|---|
| Full-time | FULL_TIME |
| Part-time | PART_TIME |
| Contract | CONTRACT |
| Internship | INTERNSHIP |

### Experience level values

| Display label | Backend value |
|---|---|
| Entry level | ENTRY_LEVEL |
| Mid level | MID_LEVEL |
| Senior level | SENIOR_LEVEL |
| Lead | LEAD |
| Executive | EXECUTIVE |

### Salary bands

The current UI uses salary bands from $10K - $50K through $950K - $1000K.
The enum values are machine-readable names such as RANGE_50K_100K. The
frontend converts those values into friendly labels.

### Location normalization

The location list is built from the state and city data in
frontend/src/pages/jobPostingOptions.js.

The selected value is stored as a normalized string such as:

~~~text
Austin, Texas
~~~

The search helper also understands state abbreviations. For example, a search
for TX can match both Austin, Texas and Dallas, TX. This is a search
normalization feature; the posting form itself uses the known city/state
options.

---

## 8. Frontend implementation

### JobPostingPage.jsx

This component contains both:

- The employer posting list.
- The create/edit modal.

Important state variables:

| State | Purpose |
|---|---|
| editingJob | Holds the job being edited; null means create mode or list mode |
| form | Holds all current form values |
| jobs | Holds the employer's loaded postings |
| search | Holds the table search text |
| statusFilter | Holds OPEN or CLOSED filter |
| isLoading | Controls the initial list loader |
| isPublishing | Controls the create/update button loader |
| changingStatusId | Prevents repeated close/reopen clicks for one job |
| pageError | Displays list or status-action errors |
| formError | Displays a general create/update error |
| fieldErrors | Displays field-specific validation errors |
| successMessage | Displays successful create/update/status feedback |

### First useEffect: load employer jobs

The first effect calls jobApi.listEmployerJobs() once when the component mounts.
The cancelled flag prevents a late response from updating state after the
component has unmounted. This protects the UI from a stale asynchronous
response.

### Second useEffect: generate a Job ID

The second effect runs only when:

- The page is in create mode.
- No existing job is being edited.
- The form does not already have a Job ID.

This prevents repeated code-generation requests on every render.

### useMemo: filter locally

The filteredJobs calculation builds a searchable string from:

- Job ID
- Title
- Location
- Work mode
- Employment type
- Experience level
- Required skills

It then applies text search and status filtering. useMemo avoids recomputing
the list when unrelated state changes.

### formFromJob(): editing existing data

When the employer clicks Edit, the saved job is converted back into form
values:

- The deadline is reduced from a date-time to the HTML date format.
- Missing optional values become empty strings.
- resumeRequired is converted to a string for the select control.
- The location is normalized to the form's city/state option.

### saveJob(): one function for create and update

The page intentionally uses one save function:

~~~text
if editingJob exists:
    PUT /api/employer/jobs/{id}
else:
    POST /api/employer/jobs
~~~

This keeps the form behavior consistent for both operations.

### Error handling

The backend uses:

~~~json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "title": "Job title is required"
  }
}
~~~

The frontend recognizes response.data.data when it is an object and places
those values in fieldErrors. This allows backend validation errors to appear
next to the correct control.

---

## 9. HTTP request layer

The frontend API wrapper is:

~~~text
frontend/src/api/jobApi.js
~~~

Important functions:

| JavaScript function | HTTP call |
|---|---|
| listEmployerJobs() | GET /employer/jobs |
| getEmployerJob(jobId) | GET /employer/jobs/{jobId} |
| getNewJobCode() | GET /employer/jobs/new-code |
| createJob(payload) | POST /employer/jobs |
| updateJob(jobId, payload) | PUT /employer/jobs/{jobId} |
| closeJob(jobId) | PATCH /employer/jobs/{jobId}/close |
| reopenJob(jobId) | PATCH /employer/jobs/{jobId}/reopen |
| listJobApplicants(jobId) | GET /employer/jobs/{jobId}/applications |
| getJobApplicant(jobId, applicationId) | GET /employer/jobs/{jobId}/applications/{applicationId} |
| updateApplicantStatus(...) | PATCH .../{applicationId}/status |
| getApplicantResume(...) | GET .../{applicationId}/resume |

apiClient.js has an Axios request interceptor. Before each request it:

1. Reads localStorage.accessToken.
2. Adds Authorization: Bearer <token> when a token exists.
3. Removes the JSON Content-Type header for FormData uploads.

The normal API base URL is /api, so Vite proxies requests to Spring Boot during
local development.

---

## 10. Backend endpoint contract

All employer job endpoints are under:

~~~text
/api/employer/jobs
~~~

All require a valid employer JWT.

| Method | Endpoint | Result | Purpose |
|---|---|---|---|
| GET | /api/employer/jobs | 200 OK | Return the current employer's jobs |
| GET | /api/employer/jobs/{jobId} | 200 OK | Return one owned job |
| GET | /api/employer/jobs/new-code | 200 OK | Generate an available Job ID |
| POST | /api/employer/jobs | 201 Created | Create an open job |
| PUT | /api/employer/jobs/{jobId} | 200 OK | Update an open owned job |
| PATCH | /api/employer/jobs/{jobId}/close | 200 OK | Change OPEN to CLOSED |
| PATCH | /api/employer/jobs/{jobId}/reopen | 200 OK | Change CLOSED to OPEN |

### Endpoints used after publication

The employer posting page is the first part of the flow. These related endpoints
complete the job seeker and applicant journey that begins when the employer
publishes a job:

| Method | Endpoint | Authentication | Why it matters |
|---|---|---|---|
| GET | /api/jobs | Optional for browsing | Return open jobs with search, salary, location, distance, and pagination filters |
| GET | /api/jobs/{jobId} | Optional for browsing | Return details for one open job and whether the current job seeker already applied |
| POST | /api/jobs/{jobId}/apply | Required job seeker token | Create one application after profile, status, deadline, resume, and duplicate checks |
| GET | /api/employer/jobs/{jobId}/applications | Required employer token | List applicants for a job owned by the employer |
| GET | /api/employer/jobs/{jobId}/applications/{applicationId} | Required employer token | Return one applicant's details |
| PATCH | /api/employer/jobs/{jobId}/applications/{applicationId}/status | Required employer token | Move an application through its review statuses |
| GET | /api/employer/jobs/{jobId}/applications/{applicationId}/resume | Required employer token | Preview or download the applicant's stored resume |
| GET | /api/employer/notifications | Required employer token | Read the employer's in-app notification feed |
| GET | /api/employer/notifications/unread-count | Required employer token | Show the unread notification count |
| PATCH | /api/employer/notifications/{notificationId}/read | Required employer token | Mark one notification as read |
| PATCH | /api/employer/notifications/read-all | Required employer token | Mark all employer notifications as read |

The public browsing endpoints accept an optional token so the server can include
the job seeker's `hasApplied` state when available. Applying and employer actions
are protected operations and require authentication.

### Standard response wrapper

Successful responses use:

~~~json
{
  "success": true,
  "message": "Job posting published successfully",
  "data": {}
}
~~~

The data property contains the job response for create, update, close, and
reopen operations. For list operations it contains an array.

### Create request example

~~~http
POST /api/employer/jobs
Authorization: Bearer <employer-token>
Content-Type: application/json
~~~

~~~json
{
  "jobCode": "JOB-4A9F2C10",
  "title": "Senior React Engineer",
  "workMode": "HYBRID",
  "location": "Austin, Texas",
  "employmentType": "FULL_TIME",
  "experienceLevel": "SENIOR_LEVEL",
  "requiredSkills": ["React", "JavaScript", "REST APIs"],
  "salaryRange": "RANGE_100K_150K",
  "salaryMin": null,
  "salaryMax": null,
  "description": "Build accessible product experiences for our platform team.",
  "applicationDeadline": "2026-12-31",
  "resumeRequired": true
}
~~~

### Create response example

~~~json
{
  "success": true,
  "message": "Job posting published successfully",
  "data": {
    "id": "67f1c9...",
    "jobCode": "JOB-4A9F2C10",
    "title": "Senior React Engineer",
    "department": null,
    "description": "Build accessible product experiences for our platform team.",
    "location": "Austin, Texas",
    "workMode": "HYBRID",
    "employmentType": "FULL_TIME",
    "experienceLevel": "SENIOR_LEVEL",
    "requiredSkills": ["React", "JavaScript", "REST APIs"],
    "salaryMin": null,
    "salaryMax": null,
    "salaryRange": "RANGE_100K_150K",
    "applicationDeadline": "2026-12-31T23:59:59",
    "resumeRequired": true,
    "status": "OPEN",
    "applicationCount": 0,
    "createdAt": "2026-08-06T09:30:00",
    "updatedAt": "2026-08-06T09:30:00"
  }
}
~~~

The exact MongoDB id and timestamps are generated at runtime.

---

## 11. What the backend does with the request

### 11.1 Interceptor runs first

JwtAuthInterceptor runs for /api/employer/**.

It checks:

1. Is the Authorization header present?
2. Does it start with Bearer?
3. Can the JWT be parsed and verified?
4. Is it still valid and not expired?
5. Does its role equal EMPLOYER?

If the request succeeds, the interceptor stores the user identity on the
request:

~~~text
authenticatedUserId
authenticatedRole
~~~

The controller reads authenticatedUserId rather than trusting a user ID
provided in the request body.

### 11.2 Controller receives the HTTP request

JobController is intentionally thin. It:

- Maps HTTP methods and URLs.
- Reads the authenticated user ID.
- Uses @Valid for bean validation.
- Calls JobService.
- Wraps the result in ApiResponse.
- Returns the correct HTTP status.

The controller does not contain MongoDB code or business rules.

### 11.3 Bean validation runs

CreateJobRequest defines the request contract:

- @NotBlank for title, location, and description.
- @NotNull for enum fields, deadline, and resume requirement.
- @NotEmpty for required skills.
- @Size limits for title, location, and description.
- @Pattern for the Job ID.
- @DecimalMin for salary values.
- @FutureOrPresent for the deadline.

If validation fails, the global exception handler returns HTTP 400 with a map
of field names to messages.

### 11.4 Service finds the employer profile

JobService.findEmployerProfile() looks up the profile by the authenticated user
ID.

The relationship is:

~~~text
JWT user ID -> EmployerProfile.userId
~~~

If no profile exists, the response is HTTP 404:

~~~text
Employer profile was not found
~~~

### 11.5 Service verifies profile completeness

For create operations, validateEmployerProfile() requires the company name,
address, phone number, and company size.

This is separate from job request validation because these values belong to the
employer profile, not the job form.

### 11.6 Service applies business rules

validateJobRequest() adds rules that are more than simple annotations:

- Deadline must exist.
- Deadline cannot be before today.
- Maximum salary cannot be below minimum salary.
- At least one nonblank required skill must remain after normalization.

Required skills are trimmed, blank items are removed, and duplicate items are
removed.

Example:

~~~text
[" React ", "React", " ", "REST APIs"]
becomes
["React", "REST APIs"]
~~~

### 11.7 Service creates the entity

For a new job, the service builds a Job entity with:

- The employer profile ID.
- A unique Job ID.
- OPEN status.
- createdAt.
- updatedAt.

The request fields are then applied to the entity.

The deadline changes from a LocalDate to a LocalDateTime at the end of that
day:

~~~text
2026-12-31 -> 2026-12-31T23:59:59.999999999
~~~

This lets someone apply during the full selected calendar date.

### 11.8 Repository saves the document

JobRepository extends MongoRepository<Job, String>. The service calls
jobRepository.save(job).

The Job entity is stored in the MongoDB jobs collection.

### 11.9 Service maps the response

The service converts the entity into EmployerJobResponse. It also calculates
the application count by calling:

~~~text
jobApplicationRepository.countByJobId(job.getId())
~~~

That count is why the employer table can show the latest application number.

### 11.10 Business-rule matrix

This matrix separates the rule from the layer that owns it. That distinction is
useful when debugging because the same user-visible failure can originate in a
different layer depending on how the request was made.

| Rule | Enforcing layer | Expected result when it fails |
|---|---|---|
| A protected employer request needs a valid JWT | `JwtAuthInterceptor` | 401 for missing or invalid authentication |
| The authenticated role must be `EMPLOYER` | `JwtAuthInterceptor` | 403 for a valid token with the wrong role |
| The employer profile must exist | `JobService.findEmployerProfile()` | 404 with employer profile not found |
| Company name, address, phone, and company size must be complete before create | `JobService.validateEmployerProfile()` | 400 business error asking the employer to complete the profile |
| Required text, enum, list, date, and size constraints must be valid | `CreateJobRequest` bean validation | 400 field-error map |
| The deadline must be today or later | Frontend `validate()` and `JobService.validateJobRequest()` | 400 and no saved job |
| Maximum salary cannot be below minimum salary | `JobService.validateJobRequest()` | 400 salary-order business error |
| At least one nonblank skill must remain after trimming and deduplication | `JobService.normalizeSkills()` | 400 required-skill business error |
| A Job ID must follow `JOB-XXXXXXXX` and remain unique | DTO pattern plus repository lookup and MongoDB unique index | 400 with a request for a new Job ID, or a persistence conflict if the race is simultaneous |
| A job can be edited only while `OPEN` | `JobService.updateJob()` | 400 active-job-only error |
| A job can be closed only from `OPEN` | `JobService.closeJob()` | 400 status-transition error |
| A job can be reopened only from `CLOSED` | `JobService.reopenJob()` | 400 status-transition error |
| Employer actions must target a job owned by that employer profile | `JobService.findOwnedJob()` | 404 without exposing another employer's job |
| Job seeker browsing returns only `OPEN` jobs | `JobPortalService.getOpenJobs()` | Closed jobs are omitted from Careers |
| An application must arrive before the stored deadline | `JobPortalService.applyForJob()` | 400 deadline-passed error |
| The same job seeker cannot apply twice to one job | `JobApplicationRepository` and service logic | 400 duplicate-application error |

The frontend version of a rule is for speed and usability. The backend version is
the authority because API clients are not required to use the Waypoint browser.

---

## 12. MongoDB data model

### Job document

The Job entity maps to:

~~~text
jobs
~~~

Important fields:

| Field | Meaning | Index behavior |
|---|---|---|
| id | MongoDB document identifier | Primary identifier |
| employerProfileId | Owner of the job | Indexed |
| jobCode | Human-readable unique Job ID | Unique index |
| title | Display title | No explicit index |
| department | Optional department | No explicit index |
| description | Job description | No explicit index |
| location | City/state text | Indexed |
| latitude | Geocoded latitude | No explicit index |
| longitude | Geocoded longitude | No explicit index |
| workMode | On-site, remote, or hybrid | No explicit index |
| employmentType | Full-time, etc. | No explicit index |
| experienceLevel | Seniority | No explicit index |
| requiredSkills | Skills needed for the role | Indexed |
| salaryMin | Legacy numeric minimum | No explicit index |
| salaryMax | Legacy numeric maximum | No explicit index |
| salaryRange | Current salary band | No explicit index |
| applicationDeadline | Last application date/time | No explicit index |
| resumeRequired | Stored employer preference | No explicit index |
| status | Lifecycle state | Indexed |
| createdAt | Creation timestamp | Descending index |
| updatedAt | Last update timestamp | No explicit index |

### Relationships

~~~text
User
  |
  | 1-to-1 through EmployerProfile.userId
  v
EmployerProfile
  |
  | 1-to-many through Job.employerProfileId
  v
Job
  |
  | 1-to-many through JobApplication.jobId
  v
JobApplication
  |
  | many-to-1 through jobSeekerProfileId
  v
JobSeekerProfile
~~~

### Relationship details in plain language

MongoDB stores these as separate documents. The relationships are represented by
ID fields rather than relational database foreign-key constraints, so the service
layer must resolve and verify them explicitly.

| From | To | Stored link | Meaning in this feature |
|---|---|---|---|
| User | EmployerProfile | `EmployerProfile.userId` | Identifies the company profile belonging to the logged-in employer account |
| EmployerProfile | Job | `Job.employerProfileId` | Gives every posting an owner and limits employer reads and writes to that owner |
| Job | JobApplication | `JobApplication.jobId` | Groups submitted applications under the role that received them |
| JobSeekerProfile | JobApplication | `JobApplication.jobSeekerProfileId` | Identifies the job seeker who applied |
| User | Notification | `Notification.recipientUserId` | Delivers in-app events to the correct employer or job seeker account |
| JobApplication | Notification | `Notification.relatedEntityId` | Lets a notification link back to an application and its action URL |

There is no direct `Job` object embedded inside a `JobApplication` document in
this flow. The application stores the job ID, and the service loads the job when
it needs the title, company, status, deadline, or owner. This keeps each document
focused, but it also means the service must handle missing related documents
carefully.

### Why ownership is checked

The URL contains a jobId, but a job ID alone is not enough to authorize an
employer. The service:

1. Finds the authenticated employer profile.
2. Finds the requested job.
3. Compares the job's employerProfileId with the authenticated employer's
   profile ID.

If they do not match, the service behaves as if the job does not exist:

~~~text
Job posting was not found
~~~

This avoids exposing another employer's job or confirming that a guessed ID
belongs to another account.

---

## 13. Job lifecycle and status

The enum defines:

~~~text
DRAFT
OPEN
CLOSED
EXPIRED
~~~

The current employer posting flow uses:

~~~text
OPEN -> CLOSED -> OPEN
~~~

### Create

New jobs are saved as OPEN, not DRAFT. There is no separate save-as-draft action
in the employer UI.

### Edit

Only OPEN jobs can be edited. The service rejects updates to closed jobs:

~~~text
Only active job postings can be edited
~~~

### Close

Only an OPEN job can be closed.

### Reopen

Only a CLOSED job can be reopened.

### Job seeker visibility

Job seeker Careers queries only OPEN jobs. Therefore:

- A newly created job is visible.
- A closed job is hidden from open job results.
- A reopened job becomes visible again.

### Deadline behavior

The application deadline is checked when an job seeker applies. The current
implementation does not automatically change the job status to EXPIRED when the
date passes.

Therefore:

- Status controls whether a job is listed as open.
- Deadline controls whether a submitted application is accepted.

EXPIRED exists in the enum but is not currently assigned by the employer
posting service.

---

## 14. What happens after a role is published

### Job seeker Careers list

Job seekers use GET /api/jobs. The job portal service starts with jobs whose
status is OPEN. It can then filter by:

- Title
- Location
- Employment type
- Work mode
- Salary minimum
- Salary maximum
- Salary range
- Date posted
- Distance

Results are sorted newest first and returned with pagination metadata.

### Job seeker job details

Job seekers use GET /api/jobs/{jobId}. Only open jobs can be retrieved from the
public job portal. Details include company name, description, location, work
mode, employment type, salary, deadline, required skills, and whether the
current job seeker has already applied.

### Job seeker applies

The job seeker uses POST /api/jobs/{jobId}/apply. The backend:

1. Finds the job seeker profile.
2. Finds the job.
3. Confirms the job is open.
4. Confirms the deadline has not passed.
5. Finds a usable resume.
6. Rejects a duplicate application.
7. Saves a JobApplication with status APPLIED.
8. Records the resume path used for that application.
9. Sends application-submitted notifications.

### Employer reviews applicants

The employer clicks a job title in the job table. The applicant page loads:

~~~text
GET /api/employer/jobs/{jobId}
GET /api/employer/jobs/{jobId}/applications
~~~

The detail page loads:

~~~text
GET /api/employer/jobs/{jobId}/applications/{applicationId}
~~~

The employer can see contact details and skills, change the application status,
preview a PDF or DOCX resume, and download a resume.

Application statuses are:

~~~text
APPLIED
UNDER_REVIEW
SHORTLISTED
REJECTED
SELECTED
WITHDRAWN
~~~

Changing a status sends a notification when the status actually changes.

### Notification service and email service concepts

Notifications are a useful supporting concept to learn because they connect a
business event to more than one delivery channel. In Waypoint, the domain service
that owns the event calls `NotificationService`; it does not make the React page
responsible for sending messages.

#### What happens when an job seeker applies

The current application path is:

1. `JobPortalService.applyForJob()` saves the `JobApplication`.
2. The service resolves the job seeker and employer user IDs, email addresses,
   job title, company name, and applicant name.
3. It calls `NotificationService.notifyApplicationSubmitted(...)`.
4. The notification service creates an in-app notification for the job seeker.
5. It sends a confirmation email to the job seeker.
6. It sends a new-application email to the employer.
7. If the employer identifiers are available, it creates an in-app notification
   that links directly to the employer's applicant detail page.

The feature therefore has one business event, `APPLICATION_SUBMITTED`, and
multiple delivery effects. The event is still considered complete if a delivery
channel fails; `NotificationService.safelyRun(...)` catches the channel failure
and logs it instead of undoing the saved application.

#### In-app notification service

`InAppNotificationService` is the persistence-facing part of the notification
flow. It creates a document in the `notifications` collection with:

- `recipientUserId`
- `notificationType`
- `title` and `message`
- `relatedEntityId` and `actionUrl`
- `isRead`, `createdAt`, and `readAt`

The employer notification controller exposes list, unread-count, mark-one-read,
and mark-all-read endpoints. The recipient user ID is taken from the verified JWT,
so one employer cannot ask for another employer's notifications by changing a
query parameter.

#### Email service concept

`EmailService` is a provider-agnostic interface:

~~~text
sendHtmlEmail(recipient, subject, renderedHtml)
~~~

The current implementation, `JavaMailSenderEmailService`, adapts that interface
to Spring's `JavaMailSender`. It validates the recipient, subject, and body,
creates a UTF-8 HTML message, applies the configured Waypoint sender address and
name, and delegates delivery to the mail provider. `EmailTemplateService` keeps
HTML presentation separate from event orchestration.

This separation is intentional:

- The job or application service knows when a business event happened.
- `NotificationService` decides which recipients and channels are relevant.
- `InAppNotificationService` stores product notifications.
- `EmailTemplateService` renders consistent HTML.
- `EmailService` sends through the configured provider.

The `NotificationType` enum includes future-facing values such as
`NEW_JOB_POSTED`, `NEW_JOB_MATCH`, and `INTERVIEW_SCHEDULED`. The current job
creation method does not publish a new-job notification yet. Adding that later
would mean defining the audience, preferences, event payload, action URL, and
delivery policy rather than putting email code inside `JobController`.

---

## 15. Errors and how to explain them

### Missing or invalid token

HTTP status: 401 Unauthorized

~~~json
{
  "success": false,
  "message": "Missing or invalid authentication token",
  "data": null
}
~~~

### Valid token with the wrong role

HTTP status: 403 Forbidden

~~~json
{
  "success": false,
  "message": "You do not have access to this resource",
  "data": null
}
~~~

### Missing employer profile or job

HTTP status: 404 Not Found

~~~json
{
  "success": false,
  "message": "Employer profile was not found",
  "data": null
}
~~~

### Validation error

HTTP status: 400 Bad Request

~~~json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "title": "Job title is required",
    "applicationDeadline": "Application deadline is required"
  }
}
~~~

### Business-rule error

HTTP status: 400 Bad Request

Examples:

~~~text
Maximum salary must be greater than or equal to minimum salary
Only active job postings can be edited
Only active job postings can be closed
Only closed job postings can be reopened
This Job ID is no longer available. Please generate a new one
~~~

The global exception handler converts these exceptions into the standard
response wrapper.

---

## 16. Test coverage

### Manual testing plan and test cases

Manual testing should prove both the visible user experience and the server-side
guarantees. Use a real employer account with a complete profile, a separate
job seeker account, a running backend on port 8081, a reachable MongoDB instance,
and at least one usable job seeker resume.

For each case, record the account used, the request URL, the response status, the
visible result, and the data that changed. A successful screen alone is not enough
for protected workflows; inspect the Network tab or backend logs when the case is
intended to verify authorization or validation.

| ID | Scenario and steps | Expected result |
|---|---|---|
| MT-01 | Open `/employer/jobs` without an access token. | The route redirects to login and no protected job-list data is returned. |
| MT-02 | Call `GET /api/employer/jobs` with no token or an invalid token. | The API returns 401 with the standard error wrapper. |
| MT-03 | Log in as an job seeker and call an employer job endpoint. | The API returns 403 and does not expose employer data. |
| MT-04 | Log in as an employer with a complete profile and open Job Postings. | The page loads the employer's jobs, shows loading feedback first, then renders rows or the empty state. |
| MT-05 | Open Job Postings for an employer with no jobs. | The table says no postings exist and points the employer to New posting. |
| MT-06 | Select New posting and wait for the form. | The route becomes `/employer/jobs/new`, the modal opens, and a disabled unique Job ID appears. |
| MT-07 | Submit the blank form. | The API is not called; required-field messages appear for title, work type, location, employment type, experience, skills, description, and deadline. |
| MT-08 | Enter an application deadline before today and submit. | The date control prevents the choice or the form shows the past-deadline error; no job is created. |
| MT-09 | Enter a valid form with one or more skills and a salary band, then publish. | The API returns 201, the success message appears, the employer returns to the list, and the row is `Active`. |
| MT-10 | Inspect the created job in MongoDB or through the employer response. | The job has the employer profile ID, unique Job ID, normalized skills, `OPEN` status, timestamps, and an end-of-day deadline. |
| MT-11 | Search by title, location, state abbreviation, work mode, or skill. | The visible rows update locally and location aliases such as a state abbreviation match the normalized location. |
| MT-12 | Filter by Active and Closed, then clear the filter. | Only the selected status is shown, and clearing the filter restores both statuses. |
| MT-13 | Edit an open job, change its description or salary, and save. | A PUT request succeeds, the row shows the new values, and `updatedAt` changes. |
| MT-14 | Close an open job, then try to edit it. | Close changes the status to `CLOSED`, the job seeker list hides the job, and Edit is disabled. |
| MT-15 | Reopen the closed job. | Reopen changes the status to `OPEN`, the job seeker list can show it again, and Edit becomes available. |
| MT-16 | Try to update, close, or reopen a job using another employer's job ID. | The API returns a not-found style response and does not reveal or modify the other employer's job. |
| MT-17 | Attempt to create a job with an incomplete employer profile. | The API returns the profile-completion business error and no job document is saved. |
| MT-18 | Submit a salary minimum greater than the maximum through the API. | Bean validation may pass, but the service returns the salary-order business error and no job is saved. |
| MT-19 | Apply to an open job with a valid job seeker profile and resume. | The API returns 201, one application is stored, and job seeker and employer notifications are attempted. |
| MT-20 | Apply twice to the same job, apply after the deadline, or apply without a usable resume. | Each invalid attempt returns its appropriate business error and does not create a duplicate application. |
| MT-21 | Open the job title from the employer table and review an applicant. | The applicant list and detail endpoints load, the employer can update status, and the job seeker receives a status notification. |
| MT-22 | Open an employer notification, mark it read, and use Mark all as read. | The unread count decreases and only notifications belonging to the employer account are changed. |
| MT-23 | Stop the backend while the Vite frontend is running, then load Job Postings. | The browser reports the proxy error `ECONNREFUSED 127.0.0.1:8081`; restarting the backend restores the flow. |

The most valuable manual test is MT-16. It proves that the feature is not merely
working for the happy path; it proves that ownership is enforced for a malicious
or mistaken job ID as well.

### Frontend tests

frontend/src/pages/JobPostingPage.test.jsx verifies:

- Employer jobs load and display.
- Active status is displayed as Active.
- Application counts appear.
- Posted timestamps appear.
- Salary bands display correctly.
- Search filters by title.
- Status filters by open or closed.
- Location searches understand full state names and abbreviations.
- Empty job state appears.
- Required-field validation prevents an API call.
- A valid form sends the expected payload.
- Close and reopen call the correct APIs.
- List errors display the backend message.

frontend/src/test/api/jobApi.test.js verifies that the API wrapper calls the
expected URLs.

### Backend service tests

backend/src/test/java/com/waypoint/service/JobServiceTest.java verifies:

- A valid job is mapped and saved as OPEN.
- The employer profile is associated with the saved job.
- Salary maximum cannot be below minimum.
- An incomplete employer profile is rejected.
- A closed job cannot be edited.
- A job owned by another employer cannot be closed.
- An open job can be closed and reopened.

### Backend portal tests

JobPortalControllerTest verifies:

- Open job lists return 200.
- Query parameters bind correctly.
- Invalid enum and numeric values return 400.
- Job details return 200.
- Missing jobs return 404.
- Applying with a valid token returns 201.
- Applying without a token returns 401.
- Duplicate-application business errors return 400.

### Run the tests

Frontend:

~~~powershell
cd frontend
npm test
~~~

Backend:

~~~powershell
cd backend
.\mvnw.cmd test
~~~

The backend runtime still needs MongoDB and environment configuration when
running the application itself. Unit and web tests provide test properties or
mocks where appropriate.

---

## 17. Troubleshooting guide

### Error: ECONNREFUSED 127.0.0.1:8081

Meaning: Vite is running, but Spring Boot is not listening on port 8081.

The frontend proxy already targets:

~~~text
http://127.0.0.1:8081
~~~

Start the backend in another terminal and wait for:

~~~text
Tomcat started on port 8081
~~~

Do not change the frontend proxy to port 8080 if another service is using that
port.

### Error: 401

Likely causes:

- The user is not logged in.
- The token was removed from local storage.
- The token expired.
- The Authorization header was not added.

Log in again and inspect the browser storage key accessToken.

### Error: 403

Likely cause: an job seeker token is trying to call an employer endpoint, or the
JWT role is not EMPLOYER.

### Error: employer profile not found

The account may have a user record but no EmployerProfile document. Complete
employer registration or profile setup.

### Error: complete employer profile information

One or more required profile values is blank:

- Company name
- Company address
- Phone number
- Company size

### Error: Job ID unavailable

The generated or submitted Job ID already exists. Request a new code and submit
again. MongoDB also has a unique index on jobCode.

### Job does not appear in Careers

Check:

1. The job status is OPEN.
2. The job was saved successfully.
3. Job seeker search filters are not excluding it.
4. The job seeker request reaches the correct backend.

Closed jobs are intentionally excluded from open job results.

### Applications fail at the deadline

The backend compares the current time with the stored end-of-day deadline. A
job is rejected after that time even if its status still says OPEN.

---

## 18. The complete sequence, explained in words

Use this explanation when presenting the feature:

~~~text
Employer
  |
  | 1. Click Job Postings
  v
React JobPostingPage
  |
  | 2. GET /api/employer/jobs with JWT
  v
JwtAuthInterceptor
  |
  | 3. Verify token and EMPLOYER role
  v
JobController
  |
  | 4. Call JobService with authenticated user ID
  v
JobService
  |
  | 5. Find employer profile
  | 6. Validate profile and request
  | 7. Build Job with owner, OPEN status, timestamps
  v
JobRepository
  |
  | 8. Save to MongoDB jobs collection
  v
JobService
  |
  | 9. Count applications and map response
  v
React JobPostingPage
  |
  | 10. Show success and refresh the posting list
  v
Job seekers can see and apply to the open job
~~~

The frontend never decides ownership or authorization. It only sends the
request. The backend makes the security and business decisions.

---

## 19. A short presentation script

### Opening

> The Employer Job Posting feature allows an employer to publish and manage
> job opportunities. It is connected to authentication, MongoDB, job seeker job
> search, applications, and applicant review.

### Frontend explanation

> The main React component is JobPostingPage. It has two responsibilities:
> displaying the employer's postings and displaying the create/edit modal. On
> load it fetches the employer's jobs. On create it requests a unique Job ID,
> validates the form, builds a JSON payload, and calls the API.

### Backend explanation

> The request goes to JobController, but the controller stays thin. The JWT
> interceptor first verifies that the request belongs to an employer. Then
> JobService finds the employer profile, validates the business rules,
> associates the job with that profile, sets the initial OPEN status, and
> saves the job through JobRepository.

### Database explanation

> A job is stored in the MongoDB jobs collection. Its employerProfileId connects
> it to the employer who owns it. The unique jobCode gives the employer a
> readable identifier, while status, createdAt, and updatedAt support lifecycle
> and display behavior.

### Lifecycle explanation

> A new posting starts as OPEN. The employer can edit or close it. Closed jobs
> cannot be edited, but they can be reopened. Job seekers only see open jobs in
> Careers, and the backend checks the deadline when an job seeker applies.

### Downstream explanation

> When an job seeker applies, the application is linked to the job and job seeker
> profile. The employer can open the applicant list, review the applicant,
> update the status, and access the resume.

### Closing

> The design separates UI, HTTP, security, business logic, persistence, and
> response mapping. That separation makes the feature easier to test and safer
> to extend.

---

## 20. Questions you may be asked

### Why are there both frontend and backend validations?

Frontend validation gives immediate feedback and prevents unnecessary calls.
Backend validation is authoritative because a user can bypass the browser and
call the API directly.

### Why does the controller not accept an employer ID in the request body?

The backend gets the user ID from the verified JWT. Trusting a user ID sent by
the browser would let one employer pretend to be another employer.

### Why does the service check job ownership?

Authentication proves who the caller is, but ownership proves whether that
caller is allowed to access this specific job. Both checks are required.

### Why is the status set to OPEN during creation?

The current product flow treats publishing as the create action. There is no
separate save-as-draft action in the employer UI.

### Why can a closed job not be edited?

Closing a job represents a deliberate lifecycle decision. Preventing edits to
closed postings keeps the historical posting stable. The employer can reopen it
first, then edit it.

### Why is the deadline sent as a date but stored as a date-time?

The user chooses a calendar day. The backend stores the end of that day so the
deadline includes the entire selected date.

### Why is jobCode unique?

It gives users a simple identifier and prevents ambiguous references. MongoDB
also enforces uniqueness with an index.

### What does requiredSkills do?

It communicates role requirements to job seekers and is used in the job seeker
application page to compare the role's required skills with the job seeker's
profile skills.

### What is a potential implementation gap?

The form stores resumeRequired, but the current application service always
requires a usable resume before applying. If the product should allow
resume-optional jobs, JobPortalService.applyForJob() should conditionally
enforce the resume rule using the job's resumeRequired value.

### What would you improve next?

Good future improvements include:

- Add a true draft workflow.
- Automatically transition open jobs to EXPIRED after the deadline.
- Make job seeker job filtering pageable at the database level instead of loading
  all open jobs into memory.
- Use a server-owned skill catalog or skill IDs.
- Add controller-level tests specifically for employer job endpoints.
- Add optimistic concurrency or version checks for simultaneous edits.
- Add audit history for status changes and job edits.
- Enforce resumeRequired consistently during application.

---

## 21. Future enhancements

The current implementation is a solid end-to-end posting and application path.
The next improvements should be chosen by the product problem they solve, not
added as disconnected features.

| Enhancement | Product value | Main engineering work |
|---|---|---|
| Draft and preview workflow | Lets an employer prepare a role before exposing it to job seekers | Add draft endpoints, draft UI, preview mode, and a deliberate publish transition |
| Automatic expiration | Makes deadline behavior visible and reduces stale open roles | Add a scheduled job or event-driven expiry process, then display `EXPIRED` consistently |
| Resume requirement enforcement | Makes the employer's Resume required choice truthful | Read `job.resumeRequired` in the application service and define the no-resume application behavior |
| New-job and skill-match notifications | Helps job seekers discover relevant opportunities | Publish a domain event, evaluate audience preferences, create in-app notifications, and queue email delivery |
| Asynchronous notification delivery | Prevents SMTP latency or outages from slowing business requests | Add an outbox or message queue, retry policy, dead-letter handling, and delivery observability |
| Database-level search and pagination | Keeps the page fast as job volume grows | Move filters and pagination from in-memory streams into MongoDB queries and indexes |
| Controlled skill catalog | Improves matching quality and prevents spelling variants | Store skill IDs or normalized catalog values instead of arbitrary strings |
| Richer screening questions | Lets employers collect role-specific information before review | Add question definitions, answer storage, validation, and applicant display |
| Audit history and concurrency checks | Protects the history of edits and simultaneous changes | Add version or updated-at checks plus an append-only job activity log |
| Better employer analytics | Shows which roles attract views and applications | Track views and funnel events, then expose privacy-aware dashboard metrics |
| Accessibility hardening | Makes the modal and table usable with keyboard and assistive technology | Add focus trapping, focus return, stronger live-region announcements, and automated accessibility checks |

### Recommended implementation order

1. Make current fields truthful: enforce `resumeRequired` and document deadline
   behavior clearly.
2. Add draft and automatic expiration because they make the lifecycle easier for
   employers to understand.
3. Move search and pagination to the database before the job collection becomes
   large.
4. Add notification events through a reliable asynchronous boundary.
5. Add richer matching, screening, analytics, and audit history after the core
   lifecycle is stable.

This order protects the main workflow first, then improves scale, communication,
and product depth.

---

## 22. Source reference

### Frontend

~~~text
frontend/src/pages/JobPostingPage.jsx
frontend/src/pages/JobPostingPage.css
frontend/src/pages/jobPostingOptions.js
frontend/src/api/jobApi.js
frontend/src/api/apiClient.js
frontend/src/routes/AppRouter.jsx
frontend/src/routes/RoleProtectedRoute.jsx
frontend/src/pages/EmployerLayout.jsx
frontend/src/pages/EmployerDashboardPage.jsx
frontend/src/pages/EmployerApplicantsPage.jsx
frontend/src/pages/EmployerApplicantDetailPage.jsx
frontend/src/pages/JobPostingPage.test.jsx
frontend/src/test/api/jobApi.test.js
~~~

### Backend

~~~text
backend/src/main/java/com/waypoint/controller/JobController.java
backend/src/main/java/com/waypoint/service/JobService.java
backend/src/main/java/com/waypoint/entity/Job.java
backend/src/main/java/com/waypoint/dto/job/CreateJobRequest.java
backend/src/main/java/com/waypoint/dto/job/EmployerJobResponse.java
backend/src/main/java/com/waypoint/repository/JobRepository.java
backend/src/main/java/com/waypoint/security/JwtAuthInterceptor.java
backend/src/main/java/com/waypoint/config/WebMvcConfig.java
backend/src/main/java/com/waypoint/exception/GlobalExceptionHandler.java
backend/src/main/java/com/waypoint/service/JobPortalService.java
backend/src/main/java/com/waypoint/controller/JobPortalController.java
backend/src/main/java/com/waypoint/service/EmployerApplicantService.java
backend/src/main/java/com/waypoint/controller/EmployerApplicantController.java
backend/src/main/java/com/waypoint/service/NotificationService.java
backend/src/main/java/com/waypoint/service/InAppNotificationService.java
backend/src/main/java/com/waypoint/service/EmailService.java
backend/src/main/java/com/waypoint/service/JavaMailSenderEmailService.java
backend/src/main/java/com/waypoint/service/EmailTemplateService.java
backend/src/main/java/com/waypoint/controller/EmployerNotificationController.java
backend/src/main/java/com/waypoint/entity/Notification.java
backend/src/test/java/com/waypoint/service/JobServiceTest.java
backend/src/test/java/com/waypoint/controller/JobPortalControllerTest.java
backend/src/test/java/com/waypoint/service/NotificationServiceTest.java
backend/src/test/java/com/waypoint/service/EmployerApplicantServiceTest.java
~~~

---

## 23. Final takeaway

The Employer Job Posting feature is a full-stack workflow, not just a form.
The browser collects and validates data, the API authenticates the request, the
service enforces business rules and ownership, MongoDB persists the job, and
the saved job becomes part of the job seeker search and application flow.

If you remember one sentence, remember this:

> An employer creates an open job through a protected API; the backend validates
> the employer and the job, stores the job with an owner and lifecycle status,
> and exposes it to job seekers for applications and employer review.
