import { ErrorState, Spinner } from "@ivorovi/ui";
import { useEffect } from "react";
import { createBrowserRouter, Navigate, useNavigate, useRouteError, type RouteObject } from "react-router";
import { CALLBACK_PATH } from "./auth/auth";
import { AppShell } from "./layout/AppShell";
import { NotFoundPage, RequirePermission } from "./routes/Guards";
import type { Access } from "./session/access";

/**
 * The OIDC callback is completed by AuthProvider, whose onSigninCallback navigates to the saved return path.
 * This route must not navigate on its own (a lazy target route may still be loading); it only falls back to
 * the dashboard if nothing navigated away within a few seconds (e.g. a bookmarked callback URL).
 */
function AuthCallback() {
  const navigate = useNavigate();
  useEffect(() => {
    const t = window.setTimeout(() => void navigate("/", { replace: true }), 5000);
    return () => window.clearTimeout(t);
  }, [navigate]);
  return <div className="flex min-h-screen items-center justify-center"><Spinner size="lg" label="Completing sign-in" /></div>;
}

const RELOAD_FLAG = "ivorovi.chunk-reload";

/** Route error boundary. A failed lazy chunk (typically after a deployment) triggers one automatic reload. */
export function RouteError() {
  const error = useRouteError();
  const message = error instanceof Error ? error.message : String(error);
  const chunkFailure = /dynamically imported module|Importing a module script failed|error loading dynamically/i.test(message);
  if (chunkFailure) {
    let reloaded = false;
    try { reloaded = sessionStorage.getItem(RELOAD_FLAG) === "1"; sessionStorage.setItem(RELOAD_FLAG, "1"); } catch { /* storage unavailable */ }
    if (!reloaded) {
      window.location.reload();
      return <div className="flex min-h-[50vh] items-center justify-center"><Spinner size="lg" label="Updating the application" /></div>;
    }
  }
  return <ErrorState title="This page could not be displayed"
    message={chunkFailure ? "A new version of Ivorovi is available. Reload the page to continue." : "An unexpected error occurred."}
    onRetry={() => window.location.reload()} />;
}

/** Lazy route module with an optional client-side permission guard (UX only; the API enforces access). */
function page(load: () => Promise<{ default: React.ComponentType }>, check?: (a: Access) => boolean): Pick<RouteObject, "lazy"> {
  return {
    lazy: async () => {
      const { default: Page } = await load();
      try { sessionStorage.removeItem(RELOAD_FLAG); } catch { /* ignore */ }
      return { Component: check ? () => <RequirePermission check={check}><Page /></RequirePermission> : Page };
    },
  };
}

const loadProgramEditorPage = () => import("./routes/academics/ProgramEditorPage");
const loadCourseEditorPage = () => import("./routes/academics/CourseEditorPage");
const loadSubjectEditorPage = () => import("./routes/academics/SubjectEditorPage");
const loadBatchEditorPage = () => import("./routes/academics/BatchEditorPage");
/** Academic routes are readable with academics.read anywhere; editors need the matching manage permission. */
const READ_ACADEMICS = (a: Access) => a.hasAnywhere("academics.read");
const loadStudentEditorPage = () => import("./routes/students/StudentEditorPage");
const READ_STUDENTS = (a: Access) => a.hasAnywhere("student.read");
const IMPORT_STUDENTS = (a: Access) => a.hasAnywhere("student.import");
const REPORTS = (a: Access) => a.hasAnywhere("report.read");
const INTEGRATIONS = (a: Access) => a.has("integration.read");
const EXAMS = (a: Access) => a.hasAnywhere("exam.session.manage");
const INCIDENTS = (a: Access) => a.has("exam.incident.manage") || a.has("exam.incident.decide");
const QUESTIONS = (a: Access) => a.hasAnywhere("question.read");
const ASSESSMENTS = (a: Access) => a.hasAnywhere("assessment.read");
const SCHEDULES = (a: Access) => a.hasAnywhere("exam.schedule.read");
const ASSESSMENT_AUTHOR = (a: Access) => a.hasAnywhere("assessment.author") || a.hasAnywhere("assessment.author.internal");
const ASSESSMENT_QUEUE = (a: Access) => a.hasAnywhere("assessment.review") || a.hasAnywhere("assessment.publish");
const QUESTION_AUTHOR = (a: Access) => a.hasAnywhere("question.author");
const QUESTION_QUEUE = (a: Access) => a.hasAnywhere("question.review") || a.hasAnywhere("question.approve");

export const routes: RouteObject[] = [
  { path: CALLBACK_PATH, element: <AuthCallback /> },
  {
    path: "/",
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { errorElement: <RouteError />, children: [
        { index: true, handle: { crumb: "Dashboard" }, ...page(() => import("./routes/DashboardPage")) },
        { path: "organization", handle: { crumb: "Organization" }, children: [
          { index: true, ...page(() => import("./routes/organization/OrgHierarchyPage"), (a) => a.hasAnywhere("org_unit.read")) },
          { path: "units/:id", handle: { crumb: "Details" }, ...page(() => import("./routes/organization/OrgUnitDetailPage"), (a) => a.hasAnywhere("org_unit.read")) },
          { path: ":typeSlug", ...page(() => import("./routes/organization/OrgUnitListPage"), (a) => a.hasAnywhere("org_unit.read")) },
        ] },
        { path: "people", handle: { crumb: "People" }, children: [
          { index: true, element: <Navigate to="/people/users" replace /> },
          { path: "users", handle: { crumb: "Users" }, children: [
            { index: true, ...page(() => import("./routes/people/UserListPage"), (a) => a.hasAnywhere("user.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/people/UserDetailPage"), (a) => a.hasAnywhere("user.read")) },
          ] },
          { path: "staff", handle: { crumb: "Staff" }, children: [
            { index: true, ...page(() => import("./routes/people/StaffListPage"), (a) => a.hasAnywhere("staff.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/people/StaffDetailPage"), (a) => a.hasAnywhere("staff.read")) },
          ] },
          { path: "invitations", handle: { crumb: "Invitations" }, ...page(() => import("./routes/people/InvitationsPage"), (a) => a.hasAnywhere("user.read")) },
        ] },
        { path: "access", handle: { crumb: "Access" }, children: [
          { index: true, element: <Navigate to="/access/roles" replace /> },
          { path: "roles", handle: { crumb: "Roles" }, children: [
            { index: true, ...page(() => import("./routes/access/RoleListPage"), (a) => a.hasAnywhere("role.read")) },
            { path: "new", handle: { crumb: "New role" }, ...page(() => import("./routes/access/RoleEditorPage"), (a) => a.hasAnywhere("role.manage")) },
            { path: "compare", handle: { crumb: "Compare" }, ...page(() => import("./routes/access/RoleComparePage"), (a) => a.hasAnywhere("role.read")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/access/RoleDetailPage"), (a) => a.hasAnywhere("role.read")) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(() => import("./routes/access/RoleEditorPage"), (a) => a.hasAnywhere("role.manage")) },
          ] },
          { path: "permissions", handle: { crumb: "Permission Catalogue" }, ...page(() => import("./routes/access/PermissionCataloguePage"), (a) => a.hasAnywhere("role.read")) },
          { path: "assignments", handle: { crumb: "Role Assignments" }, ...page(() => import("./routes/access/RoleAssignmentsPage"), (a) => a.hasAnywhere("role.read")) },
        ] },
        { path: "academics", handle: { crumb: "Academics" }, children: [
          { index: true, ...page(() => import("./routes/academics/AcademicOverviewPage"), READ_ACADEMICS) },
          { path: "structure", handle: { crumb: "Structure" }, ...page(() => import("./routes/academics/StructureTreePage"), READ_ACADEMICS) },
          { path: "programs", handle: { crumb: "Programs" }, children: [
            { index: true, ...page(() => import("./routes/academics/ProgramListPage"), READ_ACADEMICS) },
            { path: "new", handle: { crumb: "New" }, ...page(loadProgramEditorPage, (a) => a.hasAnywhere("program.manage")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/academics/ProgramDetailPage"), READ_ACADEMICS) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(loadProgramEditorPage, (a) => a.hasAnywhere("program.manage")) },
          ] },
          { path: "courses", handle: { crumb: "Courses" }, children: [
            { index: true, ...page(() => import("./routes/academics/CourseListPage"), READ_ACADEMICS) },
            { path: "new", handle: { crumb: "New" }, ...page(loadCourseEditorPage, (a) => a.hasAnywhere("program.manage")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/academics/CourseDetailPage"), READ_ACADEMICS) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(loadCourseEditorPage, (a) => a.hasAnywhere("program.manage")) },
          ] },
          { path: "subjects", handle: { crumb: "Subjects" }, children: [
            { index: true, ...page(() => import("./routes/academics/SubjectListPage"), READ_ACADEMICS) },
            { path: "new", handle: { crumb: "New" }, ...page(loadSubjectEditorPage, (a) => a.hasAnywhere("subject.manage")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/academics/SubjectDetailPage"), READ_ACADEMICS) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(loadSubjectEditorPage, (a) => a.hasAnywhere("subject.manage")) },
          ] },
          { path: "calendar", handle: { crumb: "Academic Calendar" }, children: [
            { index: true, ...page(() => import("./routes/academics/AcademicYearsPage"), READ_ACADEMICS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/academics/AcademicYearDetailPage"), READ_ACADEMICS) },
          ] },
          { path: "terms", handle: { crumb: "Terms" }, ...page(() => import("./routes/academics/TermsPage"), READ_ACADEMICS) },
          { path: "batches", handle: { crumb: "Batches" }, children: [
            { index: true, ...page(() => import("./routes/academics/BatchListPage"), READ_ACADEMICS) },
            { path: "new", handle: { crumb: "New" }, ...page(loadBatchEditorPage, (a) => a.hasAnywhere("batch.manage")) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/academics/BatchDetailPage"), READ_ACADEMICS) },
            { path: ":id/edit", handle: { crumb: "Edit" }, ...page(loadBatchEditorPage, (a) => a.hasAnywhere("batch.manage")) },
          ] },
          { path: "offerings", handle: { crumb: "Subject Offerings" }, children: [
            { index: true, ...page(() => import("./routes/academics/OfferingListPage"), READ_ACADEMICS) },
            { path: ":id", handle: { crumb: "Faculty" }, ...page(() => import("./routes/academics/OfferingDetailPage"), READ_ACADEMICS) },
          ] },
        ] },
        { path: "students", handle: { crumb: "Students" }, children: [
          { index: true, ...page(() => import("./routes/students/StudentDashboardPage"), READ_STUDENTS) },
          { path: "list", handle: { crumb: "Student List" }, ...page(() => import("./routes/students/StudentListPage"), READ_STUDENTS) },
          { path: "new", handle: { crumb: "New" }, ...page(loadStudentEditorPage, (a) => a.hasAnywhere("student.manage")) },
          { path: "settings", handle: { crumb: "Student Settings" }, ...page(() => import("./routes/students/StudentSettingsPage"), READ_STUDENTS) },
          { path: "imports", handle: { crumb: "Imports" }, children: [
            { index: true, ...page(() => import("./routes/students/imports/ImportDashboardPage"), IMPORT_STUDENTS) },
            { path: "new", handle: { crumb: "New import" }, ...page(() => import("./routes/students/imports/ImportUploadPage"), IMPORT_STUDENTS) },
            { path: "history", handle: { crumb: "History" }, ...page(() => import("./routes/students/imports/ImportHistoryPage"), IMPORT_STUDENTS) },
            { path: "templates", handle: { crumb: "Templates and rules" }, ...page(() => import("./routes/students/imports/ImportTemplatesPage"), IMPORT_STUDENTS) },
            { path: ":id", ...page(() => import("./routes/students/imports/ImportJobPage"), IMPORT_STUDENTS) },
            { path: ":id/mapping", ...page(() => import("./routes/students/imports/ImportMappingPage"), IMPORT_STUDENTS) },
            { path: ":id/validation", ...page(() => import("./routes/students/imports/ImportValidationPage"), IMPORT_STUDENTS) },
            { path: ":id/duplicates", ...page(() => import("./routes/students/imports/ImportDuplicatesPage"), IMPORT_STUDENTS) },
            { path: ":id/preview", ...page(() => import("./routes/students/imports/ImportPreviewPage"), IMPORT_STUDENTS) },
            { path: ":id/confirm", ...page(() => import("./routes/students/imports/ImportConfirmPage"), IMPORT_STUDENTS) },
            { path: ":id/progress", ...page(() => import("./routes/students/imports/ImportProgressPage"), IMPORT_STUDENTS) },
            { path: ":id/result", ...page(() => import("./routes/students/imports/ImportResultPage"), IMPORT_STUDENTS) },
            { path: ":id/errors", ...page(() => import("./routes/students/imports/ImportErrorsPage"), IMPORT_STUDENTS) },
          ] },
          { path: ":id", handle: { crumb: "Profile" }, ...page(() => import("./routes/students/StudentProfilePage"), READ_STUDENTS) },
          { path: ":id/edit", handle: { crumb: "Edit" }, ...page(loadStudentEditorPage, (a) => a.hasAnywhere("student.manage")) },
        ] },
        { path: "settings/features", handle: { crumb: "Feature Settings" }, ...page(() => import("./routes/features/FeaturesPage"), (a) => a.hasAnywhere("feature.read")) },
        { path: "settings/system", handle: { crumb: "System Settings" }, ...page(() => import("./routes/organization/SystemSettingsPage"), (a) => a.has("tenant.settings.manage")) },
        { path: "audit", handle: { crumb: "Audit Logs" }, ...page(() => import("./routes/AuditPage"), (a) => a.has("audit.read")) },
        { path: "dashboards", handle: { crumb: "Dashboards" }, children: [
          { path: "academic", handle: { crumb: "Academic" }, ...page(() => import("./routes/insights/AcademicDashboardPage"), (a) => a.hasAnywhere("academics.read")) },
          { path: "staff", handle: { crumb: "Staff" }, ...page(() => import("./routes/insights/StaffDashboardPage"), (a) => a.hasAnywhere("staff.read")) },
          { path: "imports", handle: { crumb: "Import operations" }, ...page(() => import("./routes/insights/ImportOperationsPage"), IMPORT_STUDENTS) },
        ] },
        { path: "reports", handle: { crumb: "Reports" }, children: [
          { index: true, ...page(() => import("./routes/reports/ReportsPage"), REPORTS) },
          { path: ":key", handle: { crumb: "Report" }, ...page(() => import("./routes/reports/ReportPage"), REPORTS) },
        ] },
        { path: "notifications", handle: { crumb: "Notifications" }, ...page(() => import("./routes/NotificationsPage")) },
        { path: "approvals", handle: { crumb: "Approvals" }, children: [
          { index: true, ...page(() => import("./routes/approvals/ApprovalsPage")) },
          { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/approvals/ApprovalDetailPage")) },
        ] },
        { path: "assessments", handle: { crumb: "Assessments" }, children: [
          { index: true, ...page(() => import("./routes/assessments/AssessmentsPage"), ASSESSMENTS) },
          { path: "queue", handle: { crumb: "Review & publication" }, ...page(() => import("./routes/assessments/AssessmentQueuePage"), ASSESSMENT_QUEUE) },
          { path: ":id", handle: { crumb: "Assessment" }, ...page(() => import("./routes/assessments/AssessmentPage"), ASSESSMENTS) },
          { path: ":id/edit", handle: { crumb: "Edit" }, ...page(() => import("./routes/assessments/AssessmentEditorPage"), ASSESSMENT_AUTHOR) },
          { path: "papers/:paperId", handle: { crumb: "Paper preview" }, ...page(() => import("./routes/assessments/PaperPreviewPage"), ASSESSMENTS) },
        ] },
        { path: "questions", handle: { crumb: "Question bank" }, children: [
          { index: true, ...page(() => import("./routes/questions/QuestionBanksPage"), QUESTIONS) },
          { path: "review", handle: { crumb: "Review queue" }, ...page(() => import("./routes/questions/ReviewQueuePage"), QUESTION_QUEUE) },
          { path: "taxonomy", handle: { crumb: "Taxonomy" }, ...page(() => import("./routes/questions/QuestionTaxonomyPage"), QUESTIONS) },
          { path: "policies", handle: { crumb: "Policies" }, ...page(() => import("./routes/questions/QuestionPoliciesPage"), QUESTIONS) },
          { path: "banks/:id", handle: { crumb: "Bank" }, ...page(() => import("./routes/questions/QuestionBankPage"), QUESTIONS) },
          { path: "banks/:bankId/new", handle: { crumb: "New question" }, ...page(() => import("./routes/questions/QuestionEditorPage"), QUESTION_AUTHOR) },
          { path: "banks/:bankId/assets", handle: { crumb: "Images" }, ...page(() => import("./routes/questions/QuestionAssetsPage"), QUESTIONS) },
          { path: "banks/:bankId/import", handle: { crumb: "Import" }, ...page(() => import("./routes/questions/QuestionImportPage"), QUESTION_AUTHOR) },
          { path: "items/:id", handle: { crumb: "Question" }, ...page(() => import("./routes/questions/QuestionDetailPage"), QUESTIONS) },
          { path: "items/:id/edit", handle: { crumb: "Edit" }, ...page(() => import("./routes/questions/QuestionEditorPage"), QUESTION_AUTHOR) },
        ] },
        { path: "exams", handle: { crumb: "Examinations" }, children: [
          { index: true, element: <Navigate to="/exams/portals" replace /> },
          { path: "portals", handle: { crumb: "Portals" }, ...page(() => import("./routes/exams/ExamPortalsPage"), EXAMS) },
          { path: "schedules", handle: { crumb: "Schedules" }, children: [
            { index: true, ...page(() => import("./routes/exams/SchedulesPage"), SCHEDULES) },
            { path: ":id", handle: { crumb: "Schedule" }, ...page(() => import("./routes/exams/ScheduleDetailPage"), SCHEDULES) },
          ] },
          { path: "sessions", handle: { crumb: "Readiness sessions" }, children: [
            { index: true, ...page(() => import("./routes/exams/ExamSessionsPage"), EXAMS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/exams/ExamSessionDetailPage"), EXAMS) },
          ] },
          { path: "candidates", handle: { crumb: "Candidates" }, ...page(() => import("./routes/exams/ExamCandidatesPage"), EXAMS) },
          { path: "incidents", handle: { crumb: "Incidents" }, children: [
            { index: true, ...page(() => import("./routes/exams/ExamIncidentsPage"), INCIDENTS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/exams/ExamIncidentDetailPage"), INCIDENTS) },
          ] },
        ] },
        { path: "integrations", handle: { crumb: "Integrations" }, children: [
          { index: true, ...page(() => import("./routes/integrations/IntegrationsPage"), INTEGRATIONS) },
          { path: "api-clients", handle: { crumb: "API clients" }, children: [
            { index: true, ...page(() => import("./routes/integrations/ApiClientsPage"), INTEGRATIONS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/integrations/ApiClientDetailPage"), INTEGRATIONS) },
          ] },
          { path: "service-identities", handle: { crumb: "Service identities" }, children: [
            { index: true, ...page(() => import("./routes/integrations/ServiceIdentitiesPage"), INTEGRATIONS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/integrations/ServiceIdentityDetailPage"), INTEGRATIONS) },
          ] },
          { path: "webhooks", handle: { crumb: "Webhooks" }, children: [
            { index: true, ...page(() => import("./routes/integrations/WebhooksPage"), INTEGRATIONS) },
            { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/integrations/WebhookDetailPage"), INTEGRATIONS) },
          ] },
          { path: ":id", handle: { crumb: "Details" }, ...page(() => import("./routes/integrations/IntegrationDetailPage"), INTEGRATIONS) },
        ] },
        { path: "me", handle: { crumb: "My Access" }, ...page(() => import("./routes/MyAccessPage")) },
        { path: "*", element: <NotFoundPage /> },
      ] },
    ],
  },
];

export const router = createBrowserRouter(routes);
