# Waypoint

Waypoint is a full-stack career and event management platform that connects job seekers with employers. It combines job discovery, application tracking, recruiting workflows, professional events, notifications, profile management, and secure document storage in one application.

The platform provides two role-specific experiences:

- **Job seekers** can build a professional profile, discover jobs and events, apply for positions, upload a resume, register for events, and track applications and registrations.
- **Employers** can manage a company profile, publish job postings, review applicants, update application statuses, create events, and manage event registrations.

## Table of Contents

- [Key Features](#key-features)
- [Technology Stack](#technology-stack)
- [Why These Technologies Were Chosen](#why-these-technologies-were-chosen)
- [System Architecture](#system-architecture)
- [Core Implementations](#core-implementations)
- [External Services and APIs](#external-services-and-apis)
- [Security and Authorization](#security-and-authorization)
- [Team Responsibilities](#team-responsibilities)
- [Project Structure](#project-structure)
- [Local Setup](#local-setup)
- [Testing](#testing)

## Key Features

### Authentication and Account Security

- Separate job seeker and employer registration workflows
- Email verification using a six-digit, time-limited OTP
- Secure login with role-based redirection
- JWT-based authentication for protected API requests
- Role-based authorization for job seeker and employer resources
- Forgot-password flow with OTP verification and single-use reset codes
- Authenticated password-change functionality
- BCrypt password hashing; plaintext passwords and OTPs are never stored
- Account-status and email-verification checks during login

### Job seeker Portal

- Personalized job seeker dashboard
- Job seeker profile creation, viewing, and editing
- Skills, preferred job roles, employment preferences, salary preferences, and profile-picture management
- Job discovery with keyword, location, distance, date, work-mode, employment-type, and salary filters
- Location-aware ordering of relevant jobs
- Job detail and application workflows
- Resume upload, replacement, preview, and authorized download
- Application-history and status tracking
- Event discovery with keyword, location, distance, date, type, and mode filters
- Event details, registration, cancellation, and registration tracking
- Calendar export for registered events
- In-app and email notifications for important job and event actions

### Employer Portal

- Employer dashboard with job, application, event, and registration summaries
- Company-profile management
- Job-posting creation, editing, closing, reopening, search, filtering, and pagination
- Applicant lists for each employer-owned job
- Applicant-detail view with resume preview/download
- Application-status management across the recruiting workflow
- Event creation, editing, closing, reopening, search, filtering, and pagination
- Registrant lists for employer-owned events
- In-app and email notifications for new applications and event registrations

## Technology Stack

### Frontend

| Technology | Usage |
|---|---|
| React 19 | Component-based user interface for job seeker, employer, and authentication experiences |
| JavaScript (ES modules) | Frontend application logic |
| Vite 8 | Development server, hot-module replacement, and optimized production builds |
| React Router 7 | Public, protected, nested, job seeker, and employer routes |
| Axios | Centralized HTTP client for backend API communication and JWT headers |
| React Hook Form | Performant form state and submission handling |
| Zod | Declarative client-side form validation |
| docx-preview | In-browser preview support for Microsoft Word resumes |
| Vitest | Frontend unit and component testing |
| React Testing Library | User-focused component and interaction tests |
| ESLint | Static analysis and frontend code-quality checks |

### Backend

| Technology | Usage |
|---|---|
| Java 21 | Backend language and runtime |
| Spring Boot 4 | Application configuration and backend service foundation |
| Spring Web MVC | REST controllers, validation, multipart uploads, and HTTP responses |
| Spring Data MongoDB | Repository and persistence layer for application data |
| MongoDB GridFS | Shared binary storage for resumes and job seeker profile pictures |
| Spring Mail / Jakarta Mail | SMTP delivery of HTML email notifications and OTP messages |
| Spring Security Crypto | BCrypt password and OTP hashing |
| JJWT | JWT generation and validation |
| Bean Validation | Request validation using annotations such as `@NotBlank`, `@Email`, and `@Size` |
| Lombok | Reduction of repetitive DTO, entity, and constructor code |
| Maven | Dependency management, compilation, testing, and application execution |
| JUnit 5, Mockito, AssertJ | Backend unit, controller, and service testing |

### Data and Infrastructure

| Technology | Usage |
|---|---|
| MongoDB Atlas | Shared cloud database for users, profiles, jobs, applications, events, registrations, notifications, OTPs, and file metadata |
| MongoDB GridFS | Cross-device storage of uploaded binary files inside MongoDB |
| OpenWeather API | Event weather forecasts |
| OpenStreetMap Nominatim | Geocoding job and event locations for coordinate-based distance features |
| Zippopotam.us | City-specific US ZIP-code lookup for in-person and hybrid event scheduling |
| SMTP provider | Email delivery; the provider is configurable through environment variables |

## Why These Technologies Were Chosen

### React and Reusable Components

React was selected because the application contains multiple interactive dashboards, forms, filters, tables, detail views, and role-specific layouts. Reusable components such as buttons, cards, inputs, dropdowns, tables, pagination controls, loaders, notifications, and layouts provide a consistent user experience while reducing duplicated UI logic.

### Vite

Vite provides a fast development server and near-instant frontend updates during development. It also creates an optimized production bundle with minimal configuration, making it well suited to a React project developed by multiple team members.

### React Hook Form and Zod

The application contains complex registration, profile, job-posting, event, application, and password forms. React Hook Form keeps form updates efficient, while Zod expresses validation rules in a clear, testable form. Backend validation remains authoritative, and server-side validation messages are mapped back to the corresponding fields.

### Spring Boot and Spring Web MVC

Spring Boot provides a structured architecture for REST controllers, services, repositories, configuration, validation, exception handling, email, and file uploads. Its dependency injection encourages separation of concerns and makes the business logic easier to test independently.

### MongoDB Atlas

Waypoint data naturally includes documents with nested lists and evolving profile, job, event, and notification structures. MongoDB supports that model while allowing the team to share a single cloud database across development environments. Atlas avoids dependence on a database running on one team member's computer.

### MongoDB GridFS

Resumes and profile images must remain available when the application is opened from another computer. GridFS stores binary content in the same shared MongoDB environment as the application records. This prevents uploaded files from becoming tied to one backend machine's local filesystem.

### JWT and BCrypt

JWTs allow the backend to authenticate stateless API requests and carry the authenticated user's identity and role. BCrypt is designed for password hashing and includes a work factor and salt, making it more appropriate for passwords than general-purpose hashing algorithms.

### Axios

Axios provides one shared API client for all frontend requests. A request interceptor reads the access token from browser storage and adds the `Authorization: Bearer <token>` header automatically. It also handles JSON and multipart requests consistently.

## System Architecture

Waypoint follows a layered full-stack architecture:

```text
React pages and reusable components
                |
          Frontend API modules
                |
         REST/JSON over HTTP
                |
        Spring MVC controllers
                |
        Business service layer
          /             \
MongoDB repositories       External integrations
          |             /       |       |         \
 MongoDB Atlas      SMTP   OpenWeather   Nominatim   Zippopotam.us
          |
   GridFS file storage
```

### Backend Layers

- **Controllers** define REST endpoints, validate request DTOs, resolve the authenticated user, and construct consistent API responses.
- **Services** contain authentication, authorization, job, application, event, registration, profile, notification, weather, and storage business logic.
- **Repositories** isolate MongoDB queries for users, profiles, jobs, applications, events, registrations, notifications, and OTP records.
- **DTOs** define stable API request and response contracts without exposing persistence entities directly.
- **Security components** validate JWTs and enforce authenticated user and role requirements.
- **Global exception handling** converts validation, business, authorization, and missing-resource errors into consistent JSON responses.

### Frontend Layers

- **Pages** implement complete job seeker, employer, and authentication screens.
- **Layouts** provide shared job seeker, employer, and authentication navigation structures.
- **Reusable components** provide consistent forms, feedback, navigation, cards, tables, and pagination.
- **API modules** isolate backend endpoint paths and HTTP operations from UI components.
- **Protected routes** prevent unauthenticated access to job seeker and employer areas.

## Core Implementations

### Authentication and Authorization

Registration creates a role-specific user and profile. Passwords are BCrypt-hashed before storage. A six-digit OTP is generated using `SecureRandom`, hashed before persistence, assigned a purpose, and limited to a ten-minute validity period.

After email verification, login validates the password, verification status, and account status. The backend issues a signed JWT containing the user's ID, email, and role. The frontend stores the token and the shared Axios client attaches it to protected requests. Backend interceptors and user-resolution services validate the token before protected controller operations are executed.

The password-recovery flow uses a separate `PASSWORD_RESET` OTP purpose. The code is validated before the password fields are enabled, revalidated when the password is reset, and consumed so that it cannot be reused.

### Email Notifications

Email is implemented using Spring Mail and Jakarta Mail through a configurable SMTP server. The application creates branded HTML emails with reusable templates and sends them using UTF-8 MIME messages.

Email messages include:

- New-account email-verification codes
- Password-reset codes
- Job seeker confirmation after submitting an application
- Employer notification after receiving an application
- Job seeker notification after an application-status change
- Job seeker confirmation after registering for an event
- Employer notification after receiving an event registration
- Registration cancellation and event-update messages where applicable

Email delivery can be enabled or disabled through configuration. SMTP host, port, credentials, sender address, and sender name are supplied through environment variables, so the application is not coupled to a single email provider.

### In-App Notifications

In-app notifications are stored in MongoDB and associated with the recipient's user ID. Each notification contains a type, title, message, optional related entity, destination URL, read status, creation time, and read time.

The notification system supports:

- Listing the newest notifications for the authenticated user
- Returning an unread-notification count
- Marking one notification as read
- Marking all notifications as read
- Navigating from a notification to the related application, applicant, event, or registration page

The notification orchestration service sends in-app and email messages for the same domain event while isolating channel failures. A temporary email failure, for example, does not undo a successfully completed job application or event registration.

### Weather Integration

Waypoint uses OpenWeather for event forecasts. The implementation calls:

- The OpenWeather Geocoding API to convert a city, state, and country into coordinates when required
- The OpenWeather 5 Day / 3 Hour Forecast API to retrieve forecast entries

The service selects the forecast entry closest to the event date and time and returns weather condition, description, temperature, feels-like temperature, humidity, wind speed, rain probability, and forecast time. Forecasting is optional; the backend can start without an API key and returns a clear disabled message when weather is requested without configuration.

### Location and Distance Features

OpenStreetMap's Nominatim service geocodes job and event locations. Coordinates are stored with the corresponding records and used for distance filtering and closest-first ordering. The application identifies itself through a configurable user-agent and applies request timeouts. Existing jobs and events without coordinates can be backfilled by migration services.

For in-person and hybrid event scheduling, the location form uses a dependent state, city, and ZIP-code workflow. Selecting a state limits the available cities, and selecting a city retrieves its associated US ZIP codes from Zippopotam.us. The ZIP field is a controlled selection and is checked against the loaded city-specific values before submission, preventing unrelated or random ZIP codes from being saved. Postal-service aliases and curated fallbacks cover configured cities whose municipal name differs from their preferred postal name. The street address remains user-entered because free public geocoding data does not reliably contain every valid US building number; the completed address is still geocoded through Nominatim for coordinate-based features.

### Resume Upload and Access

Job seekers can upload PDF, DOC, and DOCX resumes up to the configured maximum size, which defaults to 10 MB. Resumes are stored in the MongoDB GridFS `resumes` bucket. The stored reference is linked to the job seeker profile and job application.

The implementation supports:

- Uploading and replacing a resume
- Validating file extension and size
- Locating the most recent resume for legacy profiles
- Previewing PDF and DOCX content in the browser
- Downloading the original document
- Migrating legacy local resume files into GridFS
- Authorizing employer access based on ownership of the related job posting

An employer cannot access a resume merely by knowing its file identifier. The backend verifies that the employer owns the job associated with the application.

### Job seeker Profile Picture Upload

Job seeker profile pictures are stored in the MongoDB GridFS `profile-images` bucket. Each upload includes ownership metadata and replaces the job seeker's previous profile picture.

The file-storage layer supports:

- Shared upload, retrieval, replacement, and removal
- Content-type detection
- Sanitized file names and validated storage destinations
- Profile-image URLs that work across computers connected to the shared database
- Best-effort migration and read compatibility for legacy local files

### Job Posting and Application Management

Employers can create, edit, close, and reopen postings with job code, title, work mode, location, employment type, experience level, required skills, salary range, description, deadline, and resume requirements. The employer list includes search, status filters, and ten-record pagination.

Job seekers can browse open jobs, apply filters, view job details, and submit applications. Applied jobs are removed from the available-job results. Employers can view applicants only for jobs they own, inspect application details and resumes, and move applications through supported statuses. Job seekers can track every application and receive status notifications.

### Event and Registration Management

Employers can create and edit online, in-person, and hybrid events with event type, capacity, schedule, venue or meeting information, registration deadline, and description. In-person and hybrid event forms provide dependent state, city, and valid city-specific ZIP-code selection. Events can be closed, reopened, searched, filtered, and paginated in groups of ten.

Job seekers can discover eligible events, view event and weather details, register, cancel a registration, and track upcoming and previous registrations. Employers can view the registrants for events they own. Registration actions generate coordinated email and in-app notifications.

## External Services and APIs

| Service | Purpose | Configuration |
|---|---|---|
| MongoDB Atlas | Shared persistence for application records and GridFS files | `MONGODB_URI` or `MONGODB_URL` |
| SMTP server | HTML email, OTP, application, and event notifications | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` |
| OpenWeather Geocoding API | Resolves event locations for weather requests | `OPENWEATHER_API_KEY` |
| OpenWeather Forecast API | Provides event weather forecasts | `OPENWEATHER_API_KEY` |
| OpenStreetMap Nominatim | Geocodes job and event locations for distance features | `GEOCODING_NOMINATIM_BASE_URL`, `GEOCODING_USER_AGENT` |
| Zippopotam.us | Returns valid US ZIP-code choices for a selected event state and city | No credentials required |

No external API credentials are committed to source control. Secrets and environment-specific endpoints are supplied through environment variables.

## Security and Authorization

- Passwords and OTP values are stored as BCrypt hashes.
- JWT secrets are provided through environment configuration.
- Protected frontend routes require an access token.
- Protected backend endpoints validate the token and resolve the authenticated user.
- Job seeker and employer operations are separated by role.
- Employer job, applicant, resume, event, and registrant access is validated against resource ownership.
- Login failures use a generic response to reduce account enumeration.
- Forgot-password requests return a generic response whether or not an account exists.
- Reset OTPs are time-limited, purpose-specific, and single-use.
- Uploaded file names and paths are sanitized.
- Multipart request and file-size limits are enforced.
- CORS is restricted to configured local frontend origins during development.

## Team Responsibilities

Waypoint was developed collaboratively, with responsibilities divided across shared platform capabilities, employer workflows, and job seeker workflows.

### Shared Platform and Security

#### Sai Pragna Paduchuri — Authentication, Authorization, Email Notifications, and In-App Notifications

Implemented the cross-cutting security and communication foundation used by both job seeker and employer portals:

- Job seeker and employer registration flows
- Email-verification OTP generation, persistence, expiration, and verification
- Login workflow and role-based dashboard redirection
- BCrypt password hashing
- JWT creation, validation, request interception, and current-user resolution
- Role-based and ownership-based authorization support
- Change-password and forgot/reset-password workflows
- SMTP configuration and reusable branded HTML email templates
- Application and event email-notification orchestration
- MongoDB-backed in-app notification creation and retrieval
- Unread counts, mark-as-read, and mark-all-as-read operations
- Job seeker and employer notification pages and notification-menu integration
- Shared authentication pages, API integration, protected routing, validation, and error handling

### Employer-Side Team

#### Geethanjali Nalla — Employer Home, Profile, and Event UI Contributions

Implemented and contributed to the employer-facing presentation and account-management experience:

- Employer home/dashboard page and summary-card presentation
- Dashboard views for jobs, applications, events, and registrations
- Employer profile page
- Company information viewing and editing
- Employer layout integration and profile navigation
- Event-management UI contributions, including visual structure, forms, tables, and responsive presentation

#### Jhansi Nalla — Job Postings and Application Management

Implemented the end-to-end employer recruiting workflow:

- Employer job-posting list and management page
- New job-posting form and generated job-code workflow
- Job editing, closing, and reopening
- Job search, status filtering, and pagination
- Job attributes including skills, salary, location, employment type, work mode, experience level, deadlines, and resume requirements
- Applicant list for each employer-owned job
- Applicant-detail page
- Resume preview and download from an application
- Application-status management
- Ownership checks that restrict job and applicant access to the correct employer
- Integration between job posting, job seeker applications, notifications, and dashboard counts

#### Arun — Events and Event Registration Management

Implemented the end-to-end employer event-management workflow:

- Employer event list and management page
- Event creation and editing forms
- Online, in-person, and hybrid event configuration
- Event scheduling, venue, meeting-link, capacity, deadline, and description fields
- Event closing and reopening
- Event search, status filtering, and pagination
- Employer event-registrant lists
- Event ownership and registrant-access enforcement
- Event registration counts and dashboard integration
- Coordination with job seeker registration, cancellation, weather, email, and in-app notification workflows

### Job seeker-Side Team

#### Mahesh and Giridhar — Job seeker Profile, Edit Profile, and Events

Implemented the job seeker professional-profile and event experience:

- Job seeker profile view
- Job seeker edit-profile form
- Personal information, contact details, skills, preferred roles, employment preferences, and salary preferences
- Profile-picture upload, display, replacement, and removal
- Resume upload and profile resume management
- Job seeker event discovery page
- Event search and filtering by keyword, location, distance, date, type, and mode
- Event detail presentation
- Event registration and cancellation workflows
- Registered-events tracking and calendar export integration
- Event weather and location information presentation

#### Vasavi and Manikumar — Job seeker Home and Careers

Implemented the job seeker dashboard and job-discovery experience:

- Job seeker home/dashboard page
- Profile-completion and summary presentation
- Recommended job and event information
- Application and registration dashboard metrics
- Careers/job discovery page
- Keyword, city, distance, date, employment-type, work-mode, and salary filtering
- Closest-first job ordering and results counts
- Job list and job-detail views
- Job-application form and submission workflow
- Application tracking integration
- Hiding jobs that the job seeker has already applied to
- Careers navigation, responsive layout, empty states, and loading/error presentation

## Project Structure

```text
Waypoint/
├── backend/
│   ├── src/main/java/com/waypoint/
│   │   ├── common/       # Shared API responses and normalization
│   │   ├── config/       # MongoDB, CORS, storage, mail, and web configuration
│   │   ├── controller/   # REST API endpoints
│   │   ├── dto/          # Request and response contracts
│   │   ├── entity/       # MongoDB documents
│   │   ├── enums/        # Domain statuses and types
│   │   ├── exception/    # Business and HTTP error handling
│   │   ├── repository/   # Spring Data MongoDB repositories
│   │   ├── security/     # JWT and authenticated-user resolution
│   │   ├── service/      # Business logic and external integrations
│   │   └── util/         # Formatting and distance utilities
│   └── src/test/         # Backend tests
├── frontend/
│   ├── src/
│   │   ├── api/          # Axios client and domain API modules
│   │   ├── components/   # Reusable UI components
│   │   ├── constants/    # Shared frontend options and statuses
│   │   ├── hooks/        # Reusable React hooks
│   │   ├── pages/        # Job seeker, employer, and auth pages
│   │   ├── routes/       # Router and protected routes
│   │   ├── test/         # Frontend tests
│   │   └── utils/        # Formatting, calendar, and file helpers
│   └── public/           # Static assets and documentation
├── docs/                 # Project documentation and design materials
└── README.md
```

## Local Setup

### Prerequisites

- Java 21
- Node.js and npm
- Access to the shared MongoDB Atlas database
- SMTP credentials when email delivery is enabled
- An OpenWeather API key when weather forecasts are enabled

### Backend Environment Variables

| Variable | Required | Description |
|---|---|---|
| `MONGODB_URI` | Yes | MongoDB Atlas connection string; `MONGODB_URL` is supported as an alias |
| `JWT_SECRET` | Yes | Secret used to sign and validate JWTs |
| `MAIL_ENABLED` | No | Enables or disables outgoing email |
| `MAIL_HOST` | For email | SMTP hostname |
| `MAIL_PORT` | For email | SMTP port; defaults to `2525` locally |
| `MAIL_USERNAME` | For email | SMTP username |
| `MAIL_PASSWORD` | For email | SMTP password |
| `MAIL_FROM_ADDRESS` | No | Sender email address |
| `MAIL_FROM_NAME` | No | Sender display name |
| `OPENWEATHER_API_KEY` | No | Enables event weather forecasting |
| `GEOCODING_USER_AGENT` | Recommended | Identifies the application to Nominatim |
| `SERVER_PORT` | No | Backend port; defaults to `8081` |

### Run the Backend

```bash
cd backend
./mvnw spring-boot:run
```

The backend is available at `http://127.0.0.1:8081`. Verify it with:

```text
http://127.0.0.1:8081/health
```

### Run the Frontend

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1
```

The frontend is available at:

```text
http://127.0.0.1:5173
```

The frontend API base URL is configured with `VITE_API_BASE_URL`. For local development, it can use either the Vite `/api` proxy or the explicit backend URL:

```bash
VITE_API_BASE_URL=http://127.0.0.1:8081/api
```

## Testing

### Backend

```bash
cd backend
./mvnw test
```

The backend test suite covers controllers, services, authorization, JWT handling, notifications, email templates, storage, jobs, applications, events, registrations, profiles, weather, and location behavior.

### Frontend

```bash
cd frontend
npm test
npm run lint
npm run build
```

Frontend tests cover API modules, authentication, routing, reusable interactions, job seeker and employer pages, filters, forms, pagination, application flows, event flows, and file-related utilities.

---

Waypoint demonstrates a complete end-to-end implementation of a secure, role-based recruiting and professional-event platform using a modern React frontend, a Spring Boot backend, cloud-hosted MongoDB, shared GridFS storage, and configurable third-party integrations.
