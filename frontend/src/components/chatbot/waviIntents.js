// Mirror of the backend's MyHrOwnRecordIntent: own-record, pending, another-person and queue-count questions,
// classified by subject and aspect so a shortcut matches the backend route that answers the question. Both
// sides are checked against the shared case table src/test/fixtures/waviIntentCases.json; change them together.

const POLITE_PREFIX = new RegExp('^(?:(?:hey|hi|hello),? )?(?:wavi,? )?(?:(?:so|ok|okay|um),? )?'
  + '(?:(?:(?:can|could|would|will) you (?:please )?(?:tell me|show me|check|let me know|find out|look up|confirm)'
  + '|please (?:tell me|show me|check|let me know|confirm)|i(?:\'d| would) like to know|i want to know'
  + '|do you know|tell me|let me know|check|please)(?: (?:if|whether))?\\s+)*');
const HOW_TO = /^(?:how (?:do|can|should|would|to)|where (?:can|do|should|would)|can i|could i|may i|should i|i want to|i need to|i'd like to|i would like to|help me|(?:can|could|would|will) you help|request|obtain|order|need|want)\b|\bhow (?:do|can|should|would) i\b|\bhow to\b/;
// Timing and obligation ("When should I submit my timesheet?") are policy questions, not lookups.
const POLICY = /\b(?:polic(?:y|ies)|deadlines?|due|cut-?offs?|how long|how soon|how many days|allowed|eligib(?:le|ility)|rules?|overtime|holidays?|limits?|maximum|minimum|usually|typically|normally|supposed to|should|must|have to|need to)\b/;
const ACTION_REQUEST = /^(?:(?:(?:can|could|will|would) you|please)(?: please)?(?: (?:just|go ahead and))? )?(?:approve|reject|submit|resubmit|sign|cancel|delete|remove|change|update|edit|modify|correct|withdraw)\b/;
const REVIEWER_QUEUE = /\bmy (?:review|approval|action|attention|decision|sign-?off|queue|team|employees|direct reports|reports|staff)\b|\b(?:assigned|routed|sent) to me\b|\bin (?:my|the) organi[sz]ation\b|\bemployee timesheets\b|\bteam timesheets\b/;

const OWN = '\\bmy (?:(?:latest|last|most recent|recent|current|previous|new|pending) )?';
const FIELD = '(phone number|phone|mobile number|cell number|home address|mailing address|address'
  + '|personal email(?: address)?|email address|legal name|first name|last name|name)';
const CHANGE = '(?:change|update|edit|correction)';
const OWN_FIELD_CHANGE = new RegExp(`${OWN}${FIELD} ${CHANGE}(?: requests?)?\\b`);
const OWN_FIELD_UPDATED = new RegExp(`${OWN}${FIELD} (?:been |got |gotten |get |was |is )?(?:updated|changed)\\b`);
const OWN_PROFILE_CHANGE = new RegExp(`${OWN}(?:(?:profile|personal (?:details|information|info)|contact (?:details|info|information)) ${CHANGE}(?: requests?)?|profile requests?)\\b`);
const OWN_TIMESHEET = new RegExp(`${OWN}time ?sheets?\\b`);
const OWN_OFFER_LETTER = new RegExp(`${OWN}offer letter(?: requests?)?\\b`);
const OWN_DOCUMENT = new RegExp(`${OWN}(?:(?:document|letter|employment verification(?: letter)?|verification letter|experience letter`
  + '|employment letter|i-?983)(?: requests?)?|requests? for (?:a |an )?(?:document|letter))\\b'
  + '|\\b(?:documents|document requests|letters) (?:have|did) i (?:request|requested|ask for|asked for)\\b');

const PENDING_SUBJECTS = '(profile change requests?|profile (?:changes|updates|requests)'
  + '|(?:phone number|address|name|personal email) (?:change|update) requests?|document requests?|documents'
  + '|offer letter requests?|timesheets?)';
const PENDING_CHECK = new RegExp(`^(?:do i have (?:any )?(?:pending |open |outstanding |unapproved )?${PENDING_SUBJECTS}`
  + '(?: (?:that are |which are )?(?:still )?(?:pending|open|outstanding|waiting|in review|in progress))?'
  + `|are (?:any of )?my ${PENDING_SUBJECTS} (?:still )?(?:pending|open|outstanding|waiting|in review|in progress))$`);

const ANY_SUBJECT = `(?:(?:latest |last |most recent |recent |current )?(?:profile change|profile update|${FIELD} (?:change|update)`
  + '|time ?sheet|document request|offer letter|employee id|job title|department|work email|manager|salary|pay|phone number|address|profile))';
const OTHER_POSSESSIVE = new RegExp(`(?:^|[^a-z0-9.@_-])([a-z][a-z0-9.@_-]*)'s ${ANY_SUBJECT}`, 'g');
const OTHER_PRONOUN = new RegExp(`\\b(?:his|her|their) ${ANY_SUBJECT}`);
const EMPLOYEE_ID = /\b[a-z]{2,}[-_]?\d{2,}\b/;
const NOT_A_PERSON = /^(?:what|who|where|when|why|how|it|that|this|there|here|let|today|tomorrow|yesterday|week|month|year|day|company|organization|organisation|team|waypoint|wavi|everyone|someone|one)$/;

const QUEUE_COUNT = /^(?:how many|what number of|number of|count of|count the|do i have any|are there any|any) (?:[a-z-]+ ){0,2}(?:time ?sheets?|profile (?:change |update )?(?:requests?|changes|updates)|document requests?|documents|offer letters?)\b.*\b(?:pending|awaiting|waiting|approval|approve|approved|review|submitted|rejected|assigned|queue|outstanding|open|completed|fulfilled)\b/;
const OWN_COUNT = /\b(?:have i|did i|i've|i have|i had) (?:\w+ )?(?:submitted|requested|sent|made|filed|created|submit|request)\b|\bmy\b(?! (?:review|approval|action|attention|decision|sign-?off|queue|team|employees|direct reports|reports|staff|organi[sz]ation)\b)/;

const REASON = /\bwhy\b|\breasons?\b/;
const WHEN = /^when\b|\bwhat (?:date|day|time)\b|\bon what (?:date|day)\b/;
const APPROVED = /\b(?:approv\w*|accepted|go(?:ne)? through|went through|okayed|signed off)\b/;
const REJECTED = /\b(?:reject\w*|declined|denied|turned down|sent back|returned)\b/;
const PENDING = /\b(?:pending|waiting|awaiting|still open|in progress|under review|outstanding|not (?:yet )?(?:been )?(?:approved|reviewed|processed))\b/;
const REQUESTED = /\b(?:submit\w*|request(?:ed)?|sent|send|made|make|file[ds]?|creat\w*|raised?|put in)\b/;
const UPDATED = /\b(?:updated|changed|take effect|took effect|effective)\b/;
const STATUS_CUE = /\b(?:status|happened|happening|update on|updates on|any (?:update|news)|news|progress|outcome|result|processed|reviewed|complete[d]?|done|ready|going)\b/;
const QUESTION_LEAD = /^(?:what|what's|which|is|was|were|are|has|have|had|did|does|do|any|show|status|when|why|where is|where's|how is|how's|give|list|look up|find)\b/;

const lower = (message) => (message ?? '').toLowerCase().replace(/[‘’]/g, "'").replace(/\s+/g, ' ').trim();

export function normalizeWaviQuestion(message) {
  return lower(message).replace(/[?.!]+$/, '').trim().replace(POLITE_PREFIX, '');
}

function fieldOf(field) {
  if (/phone|mobile|cell/.test(field)) return 'PHONE_NUMBER';
  if (field.includes('address') && !field.includes('email')) return 'ADDRESS';
  if (field.includes('email')) return 'PERSONAL_EMAIL';
  return 'NAME';
}

function ownSubject(normalized) {
  const fieldChange = normalized.match(OWN_FIELD_CHANGE) || normalized.match(OWN_FIELD_UPDATED);
  if (fieldChange) return { subject: 'PROFILE_CHANGE', field: fieldOf(fieldChange[1]) };
  if (OWN_PROFILE_CHANGE.test(normalized)) return { subject: 'PROFILE_CHANGE', field: null };
  if (OWN_TIMESHEET.test(normalized)) return { subject: 'TIMESHEET', field: null };
  if (OWN_OFFER_LETTER.test(normalized)) return { subject: 'OFFER_LETTER', field: null };
  if (OWN_DOCUMENT.test(normalized)) return { subject: 'DOCUMENT_REQUEST', field: null };
  return null;
}

function aspectOf(normalized, subject, singleField) {
  const rejected = REJECTED.test(normalized);
  if (REASON.test(normalized) && (rejected || subject !== 'TIMESHEET')) return rejected ? 'REJECTION_REASON' : 'STATUS';
  if (WHEN.test(normalized)) {
    if (APPROVED.test(normalized)) return 'APPROVED_DATE';
    if (singleField && UPDATED.test(normalized)) return 'UPDATED';
    if (REQUESTED.test(normalized)) return 'REQUESTED_DATE';
    return 'STATUS';
  }
  if (rejected) return 'REJECTED';
  if (PENDING.test(normalized)) return 'PENDING';
  if (APPROVED.test(normalized)) return 'APPROVED';
  if (singleField && UPDATED.test(normalized)) return 'UPDATED';
  if (STATUS_CUE.test(normalized)) return 'STATUS';
  return null;
}

function otherPersonSubject(normalized) {
  let other = OTHER_PRONOUN.test(normalized) || EMPLOYEE_ID.test(normalized);
  for (const match of normalized.matchAll(OTHER_POSSESSIVE)) {
    if (other) break;
    other = !NOT_A_PERSON.test(match[1]);
  }
  if (!other) return null;
  if (/\btime ?sheets?\b/.test(normalized)) return 'TIMESHEET';
  if (/\boffer letters?\b/.test(normalized)) return 'OFFER_LETTER';
  if (/\bdocument(?: requests?|s)?\b/.test(normalized)) return 'DOCUMENT_REQUEST';
  if (new RegExp(`\\b(?:profile (?:change|update)|${FIELD} (?:change|update))\\b`).test(normalized)) return 'PROFILE_CHANGE';
  return /\b(?:employee id|job title|department|work email|manager|salary|pay|phone number|address|profile)\b/.test(normalized)
    ? 'PERSONAL_RECORD' : null;
}

function pendingSubject(subject) {
  if (subject.startsWith('timesheet')) return 'TIMESHEET';
  if (subject.startsWith('document')) return 'DOCUMENT_REQUEST';
  if (subject.startsWith('offer letter')) return 'OFFER_LETTER';
  return 'PROFILE_CHANGE';
}

/** { kind, subject, aspect, field } or null; kind is OWN, PENDING_CHECK, OTHER_PERSON or QUEUE_COUNT. */
export function classifyOwnRecordIntent(message) {
  const asked = lower(message);
  const normalized = normalizeWaviQuestion(message);
  if (!normalized || ACTION_REQUEST.test(asked) || ACTION_REQUEST.test(normalized)
    || HOW_TO.test(normalized) || POLICY.test(normalized)) return null;
  const question = asked.endsWith('?') || QUESTION_LEAD.test(normalized);

  const pending = normalized.match(PENDING_CHECK);
  if (pending && !REVIEWER_QUEUE.test(normalized)) {
    return { kind: 'PENDING_CHECK', subject: pendingSubject(pending[1] ?? pending[2]), aspect: 'PENDING', field: null };
  }

  const own = REVIEWER_QUEUE.test(normalized) ? null : ownSubject(normalized);
  if (own) {
    const aspect = aspectOf(normalized, own.subject, own.field !== null);
    if (!aspect && !question) return null;
    return { kind: 'OWN', subject: own.subject, aspect: aspect ?? 'STATUS', field: own.field };
  }

  const other = otherPersonSubject(normalized);
  if (other && (question || STATUS_CUE.test(normalized))) {
    return { kind: 'OTHER_PERSON', subject: other, aspect: 'STATUS', field: null };
  }

  if (QUEUE_COUNT.test(normalized) && !OWN_COUNT.test(normalized)) {
    const subject = /\btime ?sheets?\b/.test(normalized) ? 'TIMESHEET'
      : /\bprofile\b/.test(normalized) ? 'PROFILE_CHANGE' : 'DOCUMENT_REQUEST';
    return { kind: 'QUEUE_COUNT', subject, aspect: 'STATUS', field: null };
  }
  return null;
}

// --- Interpretation fallback: mirror of the backend's MyHrQuestionInterpreter -------------------------------------
// Rewrites natural, indirect, polite or misspelled wording into the canonical phrasing the routes already answer,
// and resolves a short follow-up against the user's own previous question. It only ever produces a question to
// classify; the backend decides what the verified user may see. Checked against the shared interpretationCases.

// Ordered: on equal distance the earlier word wins ("approvd" becomes "approved").
const LEXICON = ['timesheet', 'timesheets', 'profile', 'approved', 'approve', 'rejected', 'reject', 'requested',
  'request', 'requests', 'status', 'pending', 'documents', 'document', 'employee', 'manager', 'supervisor',
  'verification', 'employment', 'letter', 'offer', 'address', 'phone', 'number', 'submitted', 'submit', 'department',
  'designation', 'change', 'updated', 'update', 'review', 'waiting', 'onboarding', 'personal', 'email'];
const INFLECTIONS = ['s', 'es', 'd', 'ed', 'ing', 'er', 'ers', 'al', 'als', 'ion', 'ions', 'ment', 'ments'];
// A word ending in "e" keeps it before these ("approved") and drops it before a vowel suffix ("approving").
const AFTER_E = ['s', 'd', 'r', 'rs', 'ment', 'ments'];
const REPLACING_E = ['ing', 'al', 'als', 'ion', 'ions', 'able'];
// Ordinary words within one edit of the vocabulary.
const ORDINARY_WORDS = new Set(['states', 'statue', 'stats', 'chance', 'charge', 'charged', 'charges', 'later',
  'latter', 'personnel', 'employer', 'employers', 'manage', 'managed', 'manages', 'managing', 'pendant', 'offers',
  'letters', 'phoned', 'numbered', 'supervise', 'supervises', 'supervised', 'waiving', 'writing', 'wanting', 'summit',
  'destination', 'employed', 'reflected']);

const CONTRACTION = /\b(?:what's|whats|who's|whos|how's|hows|where's|wheres)\b/g;
const LEADING_FILLER = /^(?:(?:hi|hey|hello)(?: there)?|wavi|quick question|just (?:a )?quick question|just checking|just wondering|one more thing|sorry to bother you|sorry|also|and also|and|so|ok|okay|um|uh|well|by the way|btw|please)[,:;!]*\s+/;
const INDIRECT_WRAPPER = new RegExp('^(?:i was wondering|i\'m wondering|i am wondering|i wonder|i\'d like to know'
  + '|i would like to know|i want to know|do you (?:happen to )?know|would you happen to know|any idea|do you have any idea'
  + '|would you mind (?:telling me|checking|letting me know)|would you be able to (?:tell me|check|let me know)'
  + '|are you able to (?:tell me|check)|(?:can|could) you (?:find out|see|look up)'
  + '|(?:can|could|would|will) you (?:please )?(?:tell me|let me know|show me|confirm|check)'
  + '|please (?:tell me|let me know|check|confirm)|is it possible to (?:know|see|check|find out)'
  + '|i\'d love to know|i\'m curious|i am curious|let me know|tell me)(?: (?:if|whether))?[,:]*\\s+');
const INSTRUCTION_PREAMBLE = /^(?:ignore|disregard|forget|override) (?:all |any )?(?:(?:the |your |my )?(?:previous|prior|above|earlier|system) )?(?:instructions|rules|prompts?|guidelines)(?: and)?[,.:;!]*\s+/;
// A claimed role never changes what the signed-in user may see, so it is dropped, not believed.
const CLAIMED_ROLE = /^(?:as (?:an? |the |your )?|i'm (?:an? |the |your )?|i am (?:an? |the |your )?|speaking as (?:an? |the )?)(?:hr(?: (?:person|manager|admin|representative|rep))?|company admin(?:istrator)?|admin(?:istrator)?|manager|supervisor)[,:;.!]*\s+(?:and\s+)?/;
const TRAILING_FILLER = /(?:,?\s+(?:please|thanks|thank you|for me|right now|currently|at the moment|now|at work|here|at the company|in waypoint|on file|again))+$/;

const INTERPRET_FIELD = '(phone number|home address|mailing address|address|legal name|name|personal email|email)';
const REWRITES = [
  [/\btime[- ]?cards?\b|\btime[- ]sheets?\b|\bhours? sheets?\b/g, (match) => (match.endsWith('s') ? 'timesheets' : 'timesheet')],
  [/\bmy (?:logged |weekly |worked |work )?hours\b/g, () => 'my timesheet'],
  [/\b([a-z][a-z0-9.@_-]*)'s hours\b/g, (match, person) => `${person}'s timesheet`],
  [/\b(?:staff|employee|emp|worker|personnel) (?:id|number|no|code)\b|\bemployee identification number\b/g, () => 'employee id'],
  [/\bmy id\b/g, () => 'my employee id'],
  [/^(?:who do i (?:answer|report) to|who (?:manages|supervises) me|who am i reporting to|who is in charge of me|who do i work (?:under|for)|who is my reporting line)$/g, () => 'who is my manager'],
  [/\bmy (?:job )?position\b|\bmy job role\b|\bmy role title\b/g, () => 'my job title'],
  [/\b(?:mobile|cell)(?: phone)?(?: number)?\b|\bcontact number\b|\btelephone(?: number)?\b|\bphone no\b|\bphone\b(?! number)/g, () => 'phone number'],
  [/\be-mail\b/g, () => 'email'],
  [/\b(?:residential|street|postal|house) address\b/g, () => 'home address'],
  [new RegExp(`\\bmy new ${INTERPRET_FIELD}\\b`, 'g'), (match, field) => `my ${field} change`],
  [new RegExp(`\\b(?:my )?request (?:to|for) (?:update|change|edit|correct)(?: of)? my ${INTERPRET_FIELD}\\b`, 'g'),
    (match, field) => `my ${field} change request`],
  [/\b(?:my )?request (?:to|for) (?:update|change|edit|correct)(?: of)? my profile\b/g, () => 'my profile change request'],
  [new RegExp(`\\b${INTERPRET_FIELD} (?:edit|modification|amendment) request`, 'g'), (match, field) => `${field} change request`],
  [/(?<!profile )(?<!number )(?<!address )(?<!name )(?<!email )\b(?:change|edit|modification|amendment|correction) (requests?)\b/g,
    (match, requests) => `profile change ${requests}`],
  [/\bsigned off on\b/g, () => 'approved'],
  [/\bsign off on\b/g, () => 'approve'],
  [/\b(?:ok'd|okd|okayed|oked|greenlit|green-lit|given the green light|got the go-ahead|got the green light)\b/g, () => 'approved'],
  [/\b(?:bounced(?: back)?|kicked back|knocked back|refused|thrown out|not accepted)\b/g, () => 'rejected'],
  [/\borganisation\b/g, () => 'organization'],
  [/^(?:i )?(?:still )?(?:haven't|have not|didn't|did not|never) (?:heard|got|gotten|received)(?: anything)?(?: back)?(?: (?:about|on|regarding|for))? (my .+)$/g,
    (match, subject) => `what is the status of ${subject}`],
  [/^(?:i'm|i am) (?:still )?waiting (?:on|for) (my .+?)(?:,? any (?:news|updates?))?$/g, (match, subject) => `is ${subject} still pending`],
  [/^(?:i'm|i am) not sure (?:if|whether) (my .+?) (?:went through|was approved|got approved|has been approved)$/g,
    (match, subject) => `was ${subject} approved`],
  [/^how is (my .+?) (?:looking|going|coming along|doing|progressing)$/g, (match, subject) => `what is the status of ${subject}`],
  [/^where is (my .+?) at$/g, (match, subject) => `what is the status of ${subject}`],
  [/^what is (?:going on|happening|the latest|the story|the deal) with (my .+)$/g, (match, subject) => `what is the status of ${subject}`],
  [/^(?:any|got any|is there any) (?:word|news|updates?|progress) (?:on|about|regarding|with) (my .+)$/g,
    (match, subject) => `what is the status of ${subject}`],
  [/^has (?:anyone|anybody|someone|hr|my manager) (?:looked at|reviewed|checked|processed|seen) (my .+)$/g,
    (match, subject) => `what is the status of ${subject}`],
];
const BARE_PROFILE_FIELD = /^my (employee id|job title|department|work email|profile)$/;
const BARE_MANAGER = /^my (?:manager|supervisor|boss)$/;

const UNSUPPORTED_CONTACT = /^(?:what is|tell me|show me|show|give me|do you (?:know|have)|what) my (?:current |registered |personal |own )?(phone number|home address|mailing address|address|personal email|email address|email)$|^what (phone number|home address|address|personal email|email address|email) (?:do you|does waypoint) have$/;
const UNSUPPORTED_START_DATE = /^(?:what is|tell me) my (?:start|joining|hire|hiring|employment start) date$|^when did i (?:start|join|begin)(?: working)?(?: (?:at|with) (?:the company|waypoint|this company))?$|^when was i hired$|^how long have i (?:worked|been working|been employed)(?: (?:at|with|for) (?:the company|waypoint|this company))?$/;
const UNSUPPORTED_EMPLOYMENT_TYPE = /^what is my (?:employment type|employment status|employee type|worker type)$/;
const UNSUPPORTED_PAY = /^what is my (?:current )?(?:salary|annual salary|pay|pay rate|hourly rate|wage|wages|compensation)$|^how much (?:do i|am i) (?:earn|make|get paid|paid)(?: (?:per|a|an|each) (?:hour|year|month|week))?$/;
const SUBJECTLESS_STATUS = new RegExp('^(?:what is (?:the |my )?status|status(?: update)?|(?:any|got any) (?:updates?|news)|what is new'
  + '|(?:was|has|is|did) (?:it|that|this)(?: been| still| get)? (?:approved|rejected|accepted|declined|pending|processed|reviewed|done|through)'
  + '|did (?:it|that) go through|(?:why|when) was (?:it|that) (?:approved|rejected)|what happened(?: to it)?'
  + '|(?:what is the status of|what happened (?:to|with)|any (?:update|news) on|is|was|has|did) my '
  + '(?:request|application|submission|change)(?: (?:been |still |get )?(?:approved|rejected|pending|processed|reviewed|through))?)$');

const SUBJECT_SWAP = /^(?:(?:but|ok|okay|so),? )?(?:what about|how about|what of|and|same for|same question for|also)(?: for)? my (.+)$/;
const PROFILE_FIELD_SUBJECT = /^(employee id|job title|department|work email|profile|phone number|home address|address|personal email|email|start date|salary|pay)$/;
const MANAGER_SUBJECT = /^(?:manager|supervisor|boss)$/;
const PRONOUN = /\b(?:that one|this one|that request|this request|the request|it|that|this|they)\b/;
const ASPECT_CUE = /\b(?:approv\w*|reject\w*|declined|accepted|pending|status|go(?:ne)? through|went through|processed|reviewed|updated|submitted|done)\b|^(?:when|why)\b/;
const BARE_WHEN_OR_WHY = /^(when|why)$/;

const basicNormalize = (message) => lower(message).replace(/[?.!]+$/, '').trim();

function editDistance(a, b) {
  const d = Array.from({ length: a.length + 1 }, (row, i) => Array.from({ length: b.length + 1 }, (cell, j) => (i === 0 ? j : j === 0 ? i : 0)));
  for (let i = 1; i <= a.length; i += 1) {
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[a.length][b.length];
}

// A well-formed inflection of a vocabulary word; "approvd" is not one, so it is still corrected.
function isInflection(word) {
  return LEXICON.some((base) => {
    if (!base.endsWith('e')) return word.startsWith(base) && INFLECTIONS.includes(word.slice(base.length));
    const stem = base.slice(0, -1);
    return (word.startsWith(base) && AFTER_E.includes(word.slice(base.length)))
      || (word.startsWith(stem) && REPLACING_E.includes(word.slice(stem.length)));
  });
}

export function correctWaviWord(word) {
  if (word.length < 4 || LEXICON.includes(word) || ORDINARY_WORDS.has(word) || isInflection(word)) return word;
  const allowed = word.length >= 8 ? 2 : 1;
  let best = word;
  let bestDistance = allowed + 1;
  for (const candidate of LEXICON) {
    if (candidate[0] !== word[0] || Math.abs(candidate.length - word.length) > allowed) continue;
    const distance = editDistance(word, candidate);
    if (distance < bestDistance) {
      best = candidate;
      bestDistance = distance;
    }
  }
  return best;
}

function expandContraction(contraction) {
  const plain = contraction.replace("'", '');
  if (plain === 'whats') return 'what is';
  if (plain === 'whos') return 'who is';
  if (plain === 'hows') return 'how is';
  return 'where is';
}

function stripWording(input) {
  let text = input;
  let previous;
  do {
    previous = text;
    text = text.replace(LEADING_FILLER, '').replace(INSTRUCTION_PREAMBLE, '').replace(CLAIMED_ROLE, '')
      .replace(INDIRECT_WRAPPER, '').replace(TRAILING_FILLER, '').trim();
  } while (text !== previous);
  return text;
}

export function canonicalizeWaviQuestion(message) {
  let text = basicNormalize(message).replace(/\s+[-–—]+\s+/g, ', ');
  text = text.replace(/[a-z]+/g, (word) => correctWaviWord(word));
  text = text.replace(CONTRACTION, (match) => expandContraction(match));
  text = stripWording(text);
  for (const [pattern, replacement] of REWRITES) text = text.replace(pattern, replacement);
  text = stripWording(text);
  if (BARE_MANAGER.test(text)) return 'who is my manager';
  const field = text.match(BARE_PROFILE_FIELD);
  return field ? `what is my ${field[1]}` : text;
}

function documentPhrase(normalized) {
  const subject = normalized.match(OWN_DOCUMENT)?.[0] ?? '';
  if (subject.includes('verification')) return 'employment verification letter request';
  if (subject.includes('experience')) return 'experience letter request';
  if (subject.includes('employment letter')) return 'employment letter request';
  if (/i-?983/.test(subject)) return 'i-983 request';
  return 'document request';
}

function subjectPhrase(intent, previousQuestion) {
  switch (intent.subject) {
    case 'TIMESHEET': return 'timesheet';
    case 'OFFER_LETTER': return 'offer letter request';
    case 'DOCUMENT_REQUEST': return documentPhrase(normalizeWaviQuestion(previousQuestion));
    case 'PROFILE_CHANGE': return intent.field === null ? 'profile change request' : `${({
      PHONE_NUMBER: 'phone number', ADDRESS: 'address', PERSONAL_EMAIL: 'personal email', NAME: 'name', DESIGNATION: 'job title',
    })[intent.field]} change request`;
    default: return 'profile';
  }
}

function template(aspect, phrase) {
  switch (aspect) {
    case 'APPROVED': return `has my ${phrase} been approved`;
    case 'REJECTED': return `was my ${phrase} rejected`;
    case 'PENDING': return `is my ${phrase} still pending`;
    case 'APPROVED_DATE': return `when was my ${phrase} approved`;
    case 'REQUESTED_DATE': return `when did i submit my ${phrase}`;
    case 'REJECTION_REASON': return `why was my ${phrase} rejected`;
    case 'UPDATED': return `has my ${phrase} been updated`;
    default: return `what is the status of my ${phrase}`;
  }
}

function resolveFollowUp(canonical, previousQuestion) {
  let previous = previousQuestion == null ? null : classifyOwnRecordIntent(previousQuestion);
  if (previous && previous.kind !== 'OWN') previous = null;

  const swap = canonical.match(SUBJECT_SWAP);
  if (swap) {
    const subject = swap[1];
    if (MANAGER_SUBJECT.test(subject)) return 'who is my manager';
    if (PROFILE_FIELD_SUBJECT.test(subject)) return `what is my ${subject}`;
    const question = template(previous ? previous.aspect : 'STATUS', subject);
    return classifyOwnRecordIntent(question) ? question : null;
  }
  if (!previous || canonical.includes('my ')) return null;

  const phrase = subjectPhrase(previous, previousQuestion);
  const bare = canonical.match(BARE_WHEN_OR_WHY);
  if (bare) {
    if (bare[1] === 'why') return template('REJECTION_REASON', phrase);
    if (['REJECTED', 'REJECTION_REASON'].includes(previous.aspect)) return `when was my ${phrase} rejected`;
    return template(['APPROVED', 'APPROVED_DATE'].includes(previous.aspect) ? 'APPROVED_DATE' : 'REQUESTED_DATE', phrase);
  }
  if (!ASPECT_CUE.test(canonical)) return null;
  const pronoun = canonical.match(PRONOUN);
  if (!pronoun) return null;
  const question = `${canonical.slice(0, pronoun.index)}my ${phrase}${canonical.slice(pronoun.index + pronoun[0].length)}`;
  return classifyOwnRecordIntent(question) ? question : null;
}

/**
 * The canonical question to classify instead of the message: a follow-up resolved against the user's own previous
 * question (the last of earlierQuestions), or the canonical wording. Null when neither differs from what was asked.
 */
export function interpretWaviQuestion(message, earlierQuestions = []) {
  const canonical = canonicalizeWaviQuestion(message);
  if (!canonical) return null;
  const last = earlierQuestions.length ? earlierQuestions[earlierQuestions.length - 1] : null;
  const followUp = resolveFollowUp(canonical, last == null ? null : canonicalizeWaviQuestion(last));
  if (followUp) return followUp;
  return canonical === basicNormalize(message) ? null : canonical;
}

/** PHONE_NUMBER, PERSONAL_EMAIL, HOME_ADDRESS, START_DATE, EMPLOYMENT_TYPE or PAY: a field Wavi does not show. */
export function classifyUnsupportedField(message) {
  const canonical = canonicalizeWaviQuestion(message);
  const contact = canonical.match(UNSUPPORTED_CONTACT);
  if (contact) {
    const field = contact[1] ?? contact[2];
    if (field.includes('phone')) return 'PHONE_NUMBER';
    return field.includes('address') ? 'HOME_ADDRESS' : 'PERSONAL_EMAIL';
  }
  if (UNSUPPORTED_START_DATE.test(canonical)) return 'START_DATE';
  if (UNSUPPORTED_EMPLOYMENT_TYPE.test(canonical)) return 'EMPLOYMENT_TYPE';
  return UNSUPPORTED_PAY.test(canonical) ? 'PAY' : null;
}

export function isAmbiguousStatusQuestion(message) {
  return SUBJECTLESS_STATUS.test(canonicalizeWaviQuestion(message));
}
