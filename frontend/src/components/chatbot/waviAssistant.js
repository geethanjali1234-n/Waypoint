import { getAccessToken } from '../../auth/myHrSession';
import payrollReadinessApi from '../../api/payrollReadinessApi';
import timesheetApi from '../../api/timesheetApi';
import myHrEmployeeApi from '../../api/myHrEmployeeApi';
import myHrProfileChangeApi from '../../api/myHrProfileChangeApi';
import { conversationalReply, employeeSearchIntent, isFollowUp, workplaceQuery } from './conversationIntents';
import { navigationRegistry } from '../../services/navigation/navigationRegistry';
import { getRelevantNavigationActions, queryKnowledgeBase } from './knowledgeBase';
import { getContextActions } from './quickActions';
import { sendMyHrChatMessage } from '../../api/myHrChatApi';
import { resolveWaviActions } from './waviActions';
import { chatErrorMessage } from './waviAnswerFormat';
import { WAVI_OUT_OF_SCOPE_REPLY } from './waviScope';

const staffRoles = ['COMPANY_ADMIN', 'HR'];
const isWorkplaceRole = (role) => [...staffRoles, 'EMPLOYEE'].includes(role);

// UI filtering supplements the existing protected routes and server authorization.
export function permittedActions(actions, role) {
  if (!isWorkplaceRole(role)) return actions.filter((action) => !action.path?.startsWith('/myhr/'));
  const permitted = navigationRegistry.getActions(role).map((action) => action.path.split('?')[0]);
  return actions.filter((action) => {
    if (action.query && !action.path) return true;
    const path = action.path?.split('?')[0];
    return path && (permitted.includes(path) || path === '/myhr/change-password');
  });
}

function unwrap(response) {
  if (response?.data?.success !== true || response.data.data == null) throw new Error('Unavailable response');
  return response.data.data;
}

const MAX_HISTORY_MESSAGES = 10;

/**
 * The earlier turns of THIS login session for the server, newest last. A turn keeps the opaque stamp the server issued
 * for it (the browser never interprets it); a turn with no stamp (a local workflow-guide reply) carries none, so the
 * server never treats it as verified. Failed attempts and notices are not conversation.
 */
export function serverHistory(history, sessionKey) {
  return (history || [])
    .filter((turn) => ['user', 'assistant'].includes(turn.sender) && typeof turn.text === 'string' && turn.text.trim()
      && !turn.error && typeof turn.provenance === 'string' && turn.provenance
      && (turn.sessionKey === undefined || turn.sessionKey === sessionKey))
    .slice(-MAX_HISTORY_MESSAGES)
    .map((turn) => ({ role: turn.sender, content: turn.text.slice(0, 4000),
      ...(typeof turn.provenance === 'string' && turn.provenance ? { provenance: turn.provenance } : {}) }));
}

/**
 * A failed authenticated chat request cannot be answered through a different data path. A 404 can mean the
 * feature is disabled, but cannot prove which authorization or verification decisions would have applied.
 */
export function chatFailureOutcome(error) {
  const status = error?.response?.status;
  const serverText = String(error?.response?.data?.message ?? '');
  if (status === 404) return { eligible: false, text: 'Wavy chat is not enabled for this workspace.', retry: false };
  if (status === 503 && /usage limit/i.test(serverText)) return { eligible: false, text: 'Wavy has reached its AI usage limit for now, so it cannot answer this question. Please try again later or contact your administrator.', retry: false };
  const { text, retry } = chatErrorMessage(error);
  return { eligible: false, text, retry };
}

export async function askWavi(query, options) {
  if (getAccessToken() && isWorkplaceRole(options.role)) {
    try {
      const reply = await sendMyHrChatMessage(query.slice(0, 2000), serverHistory(options.history, options.sessionKey));
      if (reply != null && (typeof reply !== 'object' || Array.isArray(reply))) return {
        text: "The assistant's response couldn't be displayed. Please try again.", error: true, mode: 'limited', actions: [],
      };
      if (reply?.message == null || (typeof reply.message === 'string' && !reply.message.trim())) return {
        text: 'The assistant returned an empty response. Please try again.', error: true, mode: 'limited', actions: [],
      };
      if (typeof reply.message !== 'string') return {
        text: "The assistant's response couldn't be displayed. Please try again.", error: true, mode: 'limited', actions: [],
      };
      const citations = (Array.isArray(reply.citations) ? reply.citations : [])
        .filter((citation) => Number.isInteger(citation?.sourceNumber) && citation.sourceNumber > 0 && citation.sourceNumber <= 99
          && typeof citation?.title === 'string')
        .slice(0, 10)
        .map((citation) => ({ sourceNumber: citation.sourceNumber, title: citation.title,
          documentName: typeof citation.documentName === 'string' ? citation.documentName : null, hrOnly: Boolean(citation.hrOnly) }));
      return {
        text: reply.message,
        // The server's page keys decide the buttons, resolved to this role's own routes and filtered like every other action.
        actions: permittedActions(reply.message === WAVI_OUT_OF_SCOPE_REPLY ? [] : reply.actions == null
          ? getRelevantNavigationActions(query, options.role,
            (options.history || []).filter((turn) => turn.sender === 'user' && turn.provenance
              && turn.sessionKey === options.sessionKey).map((turn) => turn.text))
          : resolveWaviActions(reply.actions, options.role, query), options.role),
        source: citations.length ? 'Approved company policies' : undefined,
        sources: citations, knowledgeStatus: reply.knowledgeStatus,
        provenance: reply.historyProvenance, server: true,
        context: null,
      };
    } catch (error) {
      const outcome = chatFailureOutcome(error);
      return { text: outcome.text, error: true, mode: 'limited',
        actions: outcome.retry ? [{ label: 'Try again', query }] : [] };
    }
  }
  return { text: 'Sign in to your My HR account to use Wavy.', error: true, mode: 'limited', actions: [] };
}

async function askGuidedWavi(query, { role, route, previousQuery = '', context = null, history = [] }) {
  const cleanedQuery = workplaceQuery(query);
  const conversation = conversationalReply(query, { history, previousQuery, context })
    || (cleanedQuery !== query ? conversationalReply(cleanedQuery, { history, previousQuery, context }) : null);
  if (conversation) return conversation;
  query = cleanedQuery;
  if (context?.intent === 'workflow-guide' && (isFollowUp(query) || /^(next|continue|done|yes|yep|sure|ok|okay)[!. ]*$/i.test(query))) {
    const repeat = /simpl|explain|understand|confused|again/i.test(query);
    const step = repeat && context.step >= 0 ? context.step : context.step + 1;
    if (step >= context.steps.length) return { text: "That's the last step in this guide. The page will confirm whether your action succeeded. Want help with anything else?", actions: [], context: null, preserveTask: true };
    return { text: `${step === 0 ? "Let's take it one step at a time." : repeat ? "Let's stay on this step." : 'Next in the guide:'}\n${context.steps[step]}\n\n${repeat ? 'Which part is unclear?' : "Say 'next' when you'd like the next step."}`,
      actions: step === 0 ? context.actions : [], context: { ...context, step }, preserveTask: true };
  }
  if (isFollowUp(query) && !previousQuery) return { text: 'Which task would you like to work through? Tell me a little more.', actions: [] };
  const contextualQuery = isFollowUp(query)
    ? `${previousQuery} ${query}` : query;
  const actions = permittedActions(getContextActions(role, route), role);
  const shortcuts = actions.slice(0, 2).map(({ label, path }) => ({ label, path }));
  const deadline = /\b(deadline|cutoff|due date|when.*submit|by when.*submit)\b/i.test(contextualQuery);
  const payrollStatus = /\bpayroll\b.*\b(status|ready|readiness|check)\b|\bcheck\b.*\bpayroll\b/i.test(contextualQuery)
    && !/\b(how|explain|workflow)\b/i.test(contextualQuery);
  const ownStatus = role === 'EMPLOYEE' && /\b(timesheet|hours)\b.*\b(status|check|approved|pending)\b|\bcheck my timesheets?\b/i.test(contextualQuery)
    && !/\b(how|explain|workflow)\b/i.test(contextualQuery);

  try {
    if (/\b(my hr|my reporting manager|my manager|who do i report to)\b/i.test(query)
      && /\b(who|name|contact|tell|show)\b|^my (hr|manager)[?! ]*$/i.test(query)) {
      if (!getAccessToken()) throw new Error('Sign-in required');
      const profile = unwrap(await myHrEmployeeApi.getMine());
      const manager = profile.reportingManager;
      const isHr = manager?.roleLabel === 'HR';
      return { text: manager?.name ? isHr
        ? `Your assigned reporting manager is **${manager.name}**, who has the **HR** role. You can check the assignment on My Profile.`
        : `Your reporting manager is **${manager.name}**. Your profile doesn't identify a separate assigned HR contact, so I won't assume they're your HR representative.`
        : "I couldn't find a named reporting-manager assignment in your profile. Ask your organization administrator to confirm your HR contact.",
      actions: [{ label: 'View My Profile', path: `/myhr/${role === 'COMPANY_ADMIN' ? 'admin' : role === 'HR' ? 'hr' : 'employee'}/profile` }],
      source: 'Your authenticated profile', confirmed: true };
    }
    const employeeSearch = employeeSearchIntent(query, context);
    const base = `/myhr/${role === 'COMPANY_ADMIN' ? 'admin' : role === 'HR' ? 'hr' : 'employee'}`;
    if (employeeSearch) {
      if (!staffRoles.includes(role)) return { text: "Employee search isn't available for your role. You can view your own profile or ask HR to help you find someone.", actions: role === 'EMPLOYEE' ? [{ label: 'My Profile', path: `${base}/profile` }] : [], context: null };
      if (!employeeSearch.value) {
        const field = employeeSearch.field;
        return { text: field ? `What's the ${field === 'employeeId' ? 'employee ID' : field}?` : 'Sure! Are you looking for someone by name, employee ID, or designation?',
          context: employeeSearch, needsClarification: true, actions: field ? [] : ['name', 'employee ID', 'designation'].map((label) => ({ label: `By ${label}`, query: `by ${label}` })) };
      }
      if (!getAccessToken()) throw new Error('Sign-in required');
      const roster = unwrap(await myHrEmployeeApi.list());
      if (!Array.isArray(roster)) throw new Error('Invalid roster');
      const needle = employeeSearch.value.toLocaleLowerCase();
      const matches = roster.filter((person) => {
        const values = employeeSearch.field ? [person[employeeSearch.field]] : [person.name, person.employeeId, person.designation];
        return values.some((value) => typeof value === 'string' && value.toLocaleLowerCase().includes(needle));
      });
      const result = matches.slice(0, 5).map((person) => [person.name, person.employeeId, person.designation].filter(Boolean).join(' · '));
      return { text: matches.length ? `Found ${matches.length} matching ${matches.length === 1 ? 'person' : 'people'} in your workspace${matches.length > 5 ? ' (showing the first 5)' : ''}:\n${result.join('\n')}` : "No matches in the roster available to your account. Try another spelling, employee ID, or designation.",
        actions: [{ label: 'Open Employee Management', path: `${base}/employees` }], context: matches.length ? null : { intent: 'employee-search', field: employeeSearch.field },
        confirmed: true, source: 'Employee Management API' };
    }
    const approvalStatus = /\b(check|show|status|how many|pending)\b.*\bapprovals?\b|\bapprovals?\b.*\b(status|pending)\b/i.test(contextualQuery)
      && !/\b(how do|how to|workflow|explain)\b/i.test(contextualQuery);
    if (approvalStatus && (/timesheet/i.test(contextualQuery) || (route.includes('/timesheets') && !/profile|document|offer letter/i.test(contextualQuery)))) {
      if (!staffRoles.includes(role)) return { text: 'The team approval queue is for HR and admins. I can help you check your own timesheets instead.', actions: [{ label: 'Check my timesheet status', query: 'Check my timesheet status' }] };
      if (!getAccessToken()) throw new Error('Sign-in required');
      const records = unwrap(await timesheetApi.list());
      if (!Array.isArray(records) || !records.every((item) => typeof item.status === 'string')) throw new Error('Invalid timesheets');
      const submitted = records.filter((item) => item.status === 'SUBMITTED').length;
      return { text: `There ${submitted === 1 ? 'is' : 'are'} **${submitted}** submitted ${submitted === 1 ? 'timesheet' : 'timesheets'} visible to your account. Open the team view to review the available actions.`, actions: [{ label: 'Review timesheets', path: `${base}/timesheets?view=team` }], source: 'Timesheets API', confirmed: true };
    }
    if (approvalStatus && !/timesheet|payroll|document|offer letter/i.test(contextualQuery)) {
      if (!staffRoles.includes(role)) return { text: "I can help you check your own requests. The approval queue is available to HR and admins.", actions: [{ label: 'My Profile Requests', path: `${base}/profile-change-requests` }] };
      if (!/profile/i.test(contextualQuery) && !route.includes('profile-change')) return { text: 'Which approvals do you mean: timesheets, profile requests, or documents?', actions: [], followUps: [{ label: 'Profile approvals', query: 'Check pending profile approvals' }, { label: 'Timesheet approvals', query: 'How do I review timesheet approvals?' }] };
      if (!getAccessToken()) throw new Error('Sign-in required');
      const requests = unwrap(await myHrProfileChangeApi.listForAdmin());
      if (!Array.isArray(requests) || !requests.every((item) => typeof item.status === 'string')) throw new Error('Invalid approvals');
      const pending = requests.filter((item) => item.status === 'PENDING').length;
      return { text: `Your profile approval queue has **${pending}** pending ${pending === 1 ? 'request' : 'requests'}. Open the queue to review the details.`, actions: [{ label: 'Review profile requests', path: `${base}/profile-change-requests` }], source: 'Profile requests API', confirmed: true };
    }
    if ((deadline || payrollStatus) && staffRoles.includes(role)) {
      if (!getAccessToken()) throw new Error('Sign-in required');
      // The service authorizes the JWT actor's role and organization. No employee IDs are supplied.
      const data = unwrap(await payrollReadinessApi.getReadiness({ page: 0, size: 1 }));
      const { summary, period } = data;
      if (deadline) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(period?.payrollCutoffDate)) throw new Error('Missing cutoff');
        return { text: `The payroll cutoff returned by your workspace is **${period.payrollCutoffDate}**. Check the payroll page for submission and approval requirements.`, actions: [{ label: 'Open payroll readiness', path: actions.find((action) => action.label === 'Payroll Readiness')?.path || `/myhr/${role === 'HR' ? 'hr' : 'admin'}/payroll-readiness` }], confirmed: true, source: 'Payroll readiness API' };
      }
      if (![summary?.totalEmployees, summary?.readyForPayroll, summary?.awaitingApproval].every((value) => Number.isSafeInteger(value) && value >= 0)) throw new Error('Missing summary');
      return { text: `Here's the latest workspace summary: **${summary.readyForPayroll} of ${summary.totalEmployees}** employees ready for payroll, with **${summary.awaitingApproval}** awaiting approval.`, actions: [{ label: 'Review payroll readiness', path: `/myhr/${role === 'HR' ? 'hr' : 'admin'}/payroll-readiness` }], confirmed: true, source: 'Payroll readiness API' };
    }
    if (ownStatus) {
      if (!getAccessToken()) throw new Error('Sign-in required');
      const data = unwrap(await timesheetApi.list());
      if (!Array.isArray(data) || !data.every((item) => typeof item.status === 'string')) throw new Error('Missing timesheets');
      const approved = data.filter((item) => item.status === 'APPROVED').length;
      return { text: data.length ? `Your workspace returned **${data.length}** timesheets, including **${approved}** approved. Open My Timesheets to review individual periods.` : "No timesheets were returned for your account. Let's open My Timesheets to get started.", actions: [{ label: 'Open My Timesheets', path: '/myhr/employee/timesheets' }], confirmed: true, source: 'Timesheets API' };
    }
    if (deadline || payrollStatus) {
      return { text: "I don't have a verified deadline or payroll status available for this view. Your timesheet page or HR team can confirm the current requirements.", actions: shortcuts };
    }
    const response = queryKnowledgeBase(contextualQuery, role, route);
    // Keep the established intent engine and workflow steps, with a concise presentation.
    const lines = response.text.split('\n').map((line) => line.trim()).filter((line) => line && !/deadline|cutoff|Friday|midnight|10th/i.test(line));
    const text = (lines.slice(0, 4).join('\n') || "Let's check the relevant workspace for the current requirements.").replace(/Waypoint AI Assistant|Waypoint Assistant/g, 'Wavy');
    const responseActions = permittedActions(response.actions || [], role);
    const fallback = response.text.includes("couldn't find an exact match");
    const steps = lines.filter((line) => /^\d+[.)]\s/.test(line));
    const guide = !fallback && steps.length > 1 ? { intent: 'workflow-guide', steps, step: -1, actions: responseActions.slice(0, 1) } : null;
    return { text: fallback ? "I'm not sure which task you mean yet. Tell me what you're trying to do, and I'll help with the next step." : text,
      actions: fallback ? [] : responseActions.slice(0, 2), needsClarification: fallback,
      context: guide,
      followUps: guide ? [{ label: 'Walk me through it', query: 'walk me through it' }] : /timesheet|hours/i.test(contextualQuery) && role === 'EMPLOYEE' ? [{ label: 'Check my timesheet status', query: 'Check my timesheet status' }] : [] };
  } catch (error) {
    const status = error?.response?.status;
    return {
      text: status === 403 ? "That information isn't available for your account. I can still help with the workflows you have access to."
        : status === 401 ? "Your session needs a refresh. Sign in again, then we can try that together."
          : "I couldn't check that information right now. Try again, or open the workspace directly.",
      error: true,
      actions: status === 401 ? [{ label: 'Sign in', path: '/myhr/login' }] : [...(status === 403 ? [] : [{ label: 'Try again', query }]), ...shortcuts],
    };
  }
}
