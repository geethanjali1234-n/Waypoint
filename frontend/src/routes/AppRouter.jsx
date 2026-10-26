import { createBrowserRouter, Navigate, RouterProvider, useSearchParams } from "react-router-dom";
import DashboardPage from "../pages/DashboardPage";
import NotFoundPage from "../pages/NotFoundPage";
import EmployerRegistrationPage from "../pages/auth/EmployerRegistrationPage";
import JobSeekerRegistrationPage from "../pages/auth/JobSeekerRegistrationPage";
import VerifyOtpPage from "../pages/auth/VerifyOtpPage";
import LoginPage from "../pages/auth/LoginPage";
import ForgotPasswordPage from "../pages/auth/ForgotPasswordPage";
import ResetPasswordPage from "../pages/auth/ResetPasswordPage";
import ExperiencePage from "../pages/experience/ExperiencePage";
import MobileDownloadPage from "../pages/MobileDownloadPage";
import EmployerLayout from "../pages/EmployerLayout";
import EmployerDashboardPage from "../pages/EmployerDashboardPage";
import EmployerApplicantDetailPage from "../pages/EmployerApplicantDetailPage";
import EmployerApplicantsPage from "../pages/EmployerApplicantsPage";
import EmployerNotificationsPage from "../pages/EmployerNotificationsPage";
import EmployerEventsPage from "../pages/EmployerEventsPage";
import EmployerEventFormPage from "../pages/EmployerEventFormPage";
import EmployerEventRegistrantsPage from "../pages/EmployerEventRegistrantsPage";
import EmployerProfilePage from "../pages/EmployerProfilePage";
import JobSeekerDashboardPage from "../pages/JobSeekerDashboardPage";
import JobSeekerLayout from "../pages/JobSeekerLayout";
import JobSeekerApplicationsPage from "../pages/JobSeekerApplicationsPage";
import JobSeekerRegisteredEventsPage from "../pages/JobSeekerRegisteredEventsPage";
import JobSeekerNotificationsPage from "../pages/JobSeekerNotificationsPage";
import ChangePasswordPage from "../pages/ChangePasswordPage";
import JobSeekerContactUsPage from "../pages/JobSeekerContactUsPage";
import ProfilePage from "../pages/job-seeker/ProfilePage";
import EditProfilePage from "../pages/job-seeker/EditProfilePage";
import CareersPage from "../pages/careers/CareersPage";
import JobDetailPage from "../pages/careers/JobDetailPage";
import JobApplyPage from "../pages/careers/JobApplyPage";
import EventsPage from "../pages/events/EventsPage";
import EventRegisterPage from "../pages/events/EventRegisterPage";
import JobPostingPage from "../pages/JobPostingPage";
import MyHrProtectedRoute from "./MyHrProtectedRoute";
import MyHrRoleLandingPage from "../pages/myhr/MyHrRoleLandingPage";
import MyHrChangePasswordPage from "../pages/myhr/MyHrChangePasswordPage";
import MyHrRegularChangePasswordPage from "../pages/myhr/MyHrRegularChangePasswordPage";
import EmployeeManagementPage from "../pages/myhr/features/employeeManagement/EmployeeManagementPage";
import ProfileChangeRequestsPage from "../pages/myhr/features/profileChanges/ProfileChangeRequestsPage";
import MyHrProfilePage from "../pages/myhr/features/profile/MyHrProfilePage";
import TimesheetsPage from "../pages/myhr/features/timesheets/TimesheetsPage";
import DocumentRequestsPage from "../pages/myhr/features/documentRequests/DocumentRequestsPage";
import RequestDocumentsForm from "../pages/myhr/features/documentRequests/RequestDocumentsForm";
import EmployeeDocumentRequestForm from "../pages/myhr/features/documentRequests/EmployeeDocumentRequestForm";
import OfferLetterPreparePage from "../pages/myhr/features/documentRequests/OfferLetterPreparePage";
import OfferLetterApprovalsPage from "../pages/myhr/features/documentRequests/OfferLetterApprovalsPage";
import DocumentPreparationPage from "../pages/myhr/features/documentRequests/documentPreparation/DocumentPreparationPage";
import EmployeeDocumentSignPage from "../pages/myhr/features/documentRequests/documentPreparation/EmployeeDocumentSignPage";
import I983SigningPage from "../pages/myhr/features/documentRequests/i983/I983SigningPage";
import { GENERIC_DOCUMENT_WORKFLOW_TYPES } from "../pages/myhr/features/documentRequests/documentWorkflowTypes";
import ReportsPage from "../pages/myhr/features/reports/ReportsPage";
import AuditHistoryPage from "../pages/myhr/features/admin/AuditHistoryPage";
import OrganizationSettingsPage from "../pages/myhr/features/admin/OrganizationSettingsPage";
import RoleProtectedRoute from "./RoleProtectedRoute";
import WaypointAdminProtectedRoute from "./WaypointAdminProtectedRoute";
import WaypointAdminDashboardPage from "../pages/waypointAdmin/WaypointAdminDashboardPage";
import WaypointAdminOrganizationsPage from "../pages/waypointAdmin/WaypointAdminOrganizationsPage";
import OrganizationRegistrationPage from "../pages/auth/OrganizationRegistrationPage";

function LegacyLoginRedirect() {
  const [searchParams] = useSearchParams();
  const persona = searchParams.get('persona') === 'job-seeker' ? 'job-seeker' : 'my-hr';

  return <Navigate to={`/login?persona=${persona}`} replace />;
}

// One route pair (HR + Company Admin) per non-Offer-Letter document type,
// all pointing at the same generic DocumentPreparationPage. Offer Letter
// keeps its own dedicated routes below, unchanged.
const GENERIC_DOCUMENT_PREPARATION_ROUTES = GENERIC_DOCUMENT_WORKFLOW_TYPES.flatMap((type) => [
  {
    path: `/myhr/hr/document-requests/:requestId/${type.routeSegment}`,
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><DocumentPreparationPage role="HR" documentTypeId={type.id} /></MyHrProtectedRoute>,
  },
  {
    path: `/myhr/admin/document-requests/:requestId/${type.routeSegment}`,
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><DocumentPreparationPage role="COMPANY_ADMIN" documentTypeId={type.id} /></MyHrProtectedRoute>,
  },
]);

const router = createBrowserRouter([
  {
    path: "/",
    element: <ExperiencePage />,
  },
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    path: "/live-login",
    element: <LoginPage />,
  },
  {
    path: "/demo/login",
    element: <LegacyLoginRedirect />,
  },
  {
    path: "/experience",
    element: <ExperiencePage />,
  },
  {
    path: "/download",
    element: <MobileDownloadPage />,
  },
  {
    path: "/mobile",
    element: <MobileDownloadPage />,
  },
  {
    path: "/app",
    element: <MobileDownloadPage />,
  },
  {
    path: "/showcase",
    element: <ExperiencePage />,
  },
  {
    path: "/discover",
    element: <ExperiencePage />,
  },
  {
    path: "/register/employer",
    element: <EmployerRegistrationPage />,
  },
  {
    path: "/register/organization",
    element: <OrganizationRegistrationPage />,
  },
  {
    path: "/register/job-seeker",
    element: <JobSeekerRegistrationPage />,
  },
  {
    path: "/verify-email",
    element: <VerifyOtpPage />,
  },
  {
    path: "/forgot-password",
    element: <ForgotPasswordPage />,
  },
  {
    path: "/reset-password",
    element: <ResetPasswordPage />,
  },
  {
    path: "/dashboard",
    element: <DashboardPage />,
  },
  {
    path: "/employer",
    element: (
      <RoleProtectedRoute>
        <EmployerLayout />
      </RoleProtectedRoute>
    ),
    children: [
      {
        path: "dashboard",
        element: <EmployerDashboardPage />,
      },
      {
        path: "jobs",
        element: <JobPostingPage />,
      },
      {
        path: "jobs/new",
        element: <JobPostingPage />,
      },
      {
        path: "jobs/:jobId/applicants",
        element: <EmployerApplicantsPage />,
      },
      {
        path: "jobs/:jobId/applicants/:applicationId",
        element: <EmployerApplicantDetailPage />,
      },
      {
        path: "notifications",
        element: <EmployerNotificationsPage />,
      },
      {
        path: "events",
        element: <EmployerEventsPage />,
      },
      {
        path: "events/new",
        element: <EmployerEventFormPage />,
      },
      {
        path: "events/:eventId/edit",
        element: <EmployerEventFormPage />,
      },
      {
        path: "events/:eventId/registrants",
        element: <EmployerEventRegistrantsPage />,
      },
      {
        path: "profile",
        element: <EmployerProfilePage />,
      },
      { path: "change-password", element: <ChangePasswordPage /> },
    ],
  },
  {
    path: "/employee/*",
    element: <Navigate to="/myhr/employee" replace />,
  },
  {
    path: "/myhr/login",
    element: <LoginPage forcedWorkspace="MY_HR" />,
  },
  {
    path: "/waypoint-admin",
    element: <Navigate to="/waypoint-admin/dashboard" replace />,
  },
  {
    path: "/waypoint-admin/dashboard",
    element: (
      <WaypointAdminProtectedRoute>
        <WaypointAdminDashboardPage />
      </WaypointAdminProtectedRoute>
    ),
  },
  {
    path: "/waypoint-admin/organizations",
    element: (
      <WaypointAdminProtectedRoute>
        <WaypointAdminOrganizationsPage />
      </WaypointAdminProtectedRoute>
    ),
  },
  {
    path: "/waypoint-admin/first-login/change-password",
    element: (
      <WaypointAdminProtectedRoute passwordChangeOnly>
        <MyHrChangePasswordPage />
      </WaypointAdminProtectedRoute>
    ),
  },
  {
    path: "/myhr/first-login/change-password",
    element: (
      <MyHrProtectedRoute
        allowedRoles={["COMPANY_ADMIN", "HR", "EMPLOYEE"]}
        passwordChangeOnly
      >
        <MyHrChangePasswordPage />
      </MyHrProtectedRoute>
    ),
  },
  {
    path: "/myhr/change-password",
    element: (
      <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN", "HR", "EMPLOYEE"]}>
        <MyHrRegularChangePasswordPage />
      </MyHrProtectedRoute>
    ),
  },
  {
    path: "/myhr/hr",
    element: <Navigate to="/myhr/hr/dashboard" replace />,
  },
  {
    path: "/myhr/hr/dashboard",
    element: (
      <MyHrProtectedRoute allowedRoles={["HR"]}>
        <MyHrRoleLandingPage role="HR" />
      </MyHrProtectedRoute>
    ),
  },
  {
    path: "/myhr/hr/employees",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><EmployeeManagementPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/profile",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><MyHrProfilePage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/profile-change-requests",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><ProfileChangeRequestsPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/timesheets",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><TimesheetsPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><DocumentRequestsPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/new",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><RequestDocumentsForm role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/request-from-manager",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><EmployeeDocumentRequestForm role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/:requestId",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><DocumentRequestsPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/:requestId/offer-letter",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><OfferLetterPreparePage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/:requestId/sign",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><EmployeeDocumentSignPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/document-requests/:requestId/i983-sign",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><I983SigningPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/hr/reports",
    element: <MyHrProtectedRoute allowedRoles={["HR"]}><ReportsPage role="HR" /></MyHrProtectedRoute>,
  },
  {
    // Payroll Readiness now lives as a tab on the Timesheets page; this
    // keeps old links (e.g. from readiness reminder notifications) working.
    path: "/myhr/hr/payroll-readiness",
    element: <Navigate to="/myhr/hr/timesheets?view=payroll" replace />,
  },
  {
    path: "/myhr/admin",
    element: <Navigate to="/myhr/admin/dashboard" replace />,
  },
  {
    path: "/myhr/admin/dashboard",
    element: (
      <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}>
        <MyHrRoleLandingPage role="COMPANY_ADMIN" />
      </MyHrProtectedRoute>
    ),
  },
  {
    path: "/myhr/admin/employees",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><EmployeeManagementPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/profile",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><MyHrProfilePage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/profile-change-requests",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><ProfileChangeRequestsPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/timesheets",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><TimesheetsPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><DocumentRequestsPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests/new",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><RequestDocumentsForm role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests/:requestId",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><DocumentRequestsPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/offer-letter-approvals",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><OfferLetterApprovalsPage /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/offer-letter-approvals/:requestId",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><OfferLetterApprovalsPage /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests/:requestId/offer-letter",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><OfferLetterPreparePage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests/:requestId/sign",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><EmployeeDocumentSignPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/document-requests/:requestId/i983-sign",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><I983SigningPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  ...GENERIC_DOCUMENT_PREPARATION_ROUTES,
  {
    path: "/myhr/admin/reports",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><ReportsPage role="COMPANY_ADMIN" /></MyHrProtectedRoute>,
  },
  {
    // Payroll Readiness now lives as a tab on the Timesheets page; this
    // keeps old links (e.g. from readiness reminder notifications) working.
    path: "/myhr/admin/payroll-readiness",
    element: <Navigate to="/myhr/admin/timesheets?view=payroll" replace />,
  },
  {
    path: "/myhr/admin/audit",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><AuditHistoryPage /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/admin/organization",
    element: <MyHrProtectedRoute allowedRoles={["COMPANY_ADMIN"]}><OrganizationSettingsPage /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee",
    element: <Navigate to="/myhr/employee/dashboard" replace />,
  },
  {
    path: "/myhr/employee/dashboard",
    element: (
      <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}>
        <MyHrRoleLandingPage role="EMPLOYEE" />
      </MyHrProtectedRoute>
    ),
  },
  {
    path: "/myhr/employee/profile",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><MyHrProfilePage role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/profile-change-requests",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><ProfileChangeRequestsPage role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/timesheets",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><TimesheetsPage role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/document-requests",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><DocumentRequestsPage role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/document-requests/new",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><EmployeeDocumentRequestForm role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/document-requests/:requestId",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><DocumentRequestsPage role="EMPLOYEE" /></MyHrProtectedRoute>,
  },
  {
    path: "/myhr/employee/document-requests/:requestId/sign",
    element: <MyHrProtectedRoute allowedRoles={["EMPLOYEE"]}><EmployeeDocumentSignPage /></MyHrProtectedRoute>,
  },
  {
    path: "/job-seeker",
    element: (
      <RoleProtectedRoute>
        <JobSeekerLayout />
      </RoleProtectedRoute>
    ),
    children: [
      {
        path: "dashboard",
        element: <JobSeekerDashboardPage />,
      },
      {
        path: "profile",
        element: <ProfilePage />,
      },
      {
        path: "profile/edit",
        element: <EditProfilePage />,
      },
      {
        path: "applications",
        element: <JobSeekerApplicationsPage />,
      },
      {
        path: "registrations",
        element: <JobSeekerRegisteredEventsPage />,
      },
      {
        path: "notifications",
        element: <JobSeekerNotificationsPage />,
      },
      {
        path: "change-password",
        element: <ChangePasswordPage />,
      },
      {
        path: "contact-us",
        element: <JobSeekerContactUsPage />,
      },
    ],
  },
  {
    path: "/careers",
    element: (
      <RoleProtectedRoute>
        <JobSeekerLayout />
      </RoleProtectedRoute>
    ),
    children: [
      { index: true, element: <CareersPage /> },
      { path: ":jobId", element: <JobDetailPage /> },
      { path: ":jobId/apply", element: <JobApplyPage /> },
    ],
  },
  {
    path: "/events",
    element: (
      <RoleProtectedRoute>
        <JobSeekerLayout />
      </RoleProtectedRoute>
    ),
    children: [
      { index: true, element: <EventsPage /> },
      { path: ":eventId/register", element: <EventRegisterPage /> },
    ],
  },
  {
    path: "*",
    element: <NotFoundPage />,
  },
]);

export default function AppRouter() {
  return <RouterProvider router={router} />;
}
