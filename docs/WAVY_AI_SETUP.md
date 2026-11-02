# Wavy live conversation and verified workplace tools

Wavy reuses the existing React panel, JWT authentication, Spring Boot services and
authorized read-only tools. Local Ollama is the default provider, using its native chat API.
No model SDK, vector database, paid API key, or browser-side key is required.

## Local AI (default, no per-request provider charges)

Install Ollama on the **backend machine**, then download the instruction-focused model:

```powershell
ollama pull qwen3:4b-instruct-2507-q4_K_M
```

Restart the backend. Its defaults enable local AI at `http://127.0.0.1:11434` with this model.
Wavy checks that the model is installed, supports tools, and is not a cloud-backed alias.
If unavailable, it uses the existing workflow guide and authorized API lookups. It never
automatically switches to Anthropic or another paid service, even if an API key is present.

Windows setup/start helper (run from the repository root):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Start-WavyLocal.ps1 -SetupOnly
# Load your existing backend environment, including JWT_SECRET and database settings.
# With JAVA_HOME pointing to JDK 21; stop your previous backend before starting another:
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Start-WavyLocal.ps1
```

The command's execution-policy override applies only to that PowerShell process; it does not
change machine policy. The helper disables cloud features for an Ollama server it starts. For an already-running
Ollama server, set `OLLAMA_NO_CLOUD=1` in that server's environment and restart Ollama to
disable cloud features globally. The application independently rejects remote model metadata.

Optional backend overrides:

```text
WAVY_AI_ENABLED=true
WAVY_AI_PROVIDER=ollama
WAVY_AI_MODEL=qwen3:4b-instruct-2507-q4_K_M
WAVY_OLLAMA_URL=http://127.0.0.1:11434
```

Use a machine with about 16 GB RAM as a testing starting point. The model download is
approximately 2.5 GB; runtime memory also includes an 8,192-token context and generation buffers.
CPU-only response latency varies. Requests are bounded to 700 generated tokens per round,
three model rounds and a 30-second read timeout per local round. The browser allows 100 seconds.
Keep the machine running for shared access; electricity and server hosting still have costs.

For deployed/mobile users, Ollama runs behind the backend, not on employees' phones. A local
laptop installation does not automatically enable AI on your deployed server. In Docker use
`http://ollama:11434` on a private container network, or `http://host.docker.internal:11434`
when appropriate. Private IPv4 server addresses are also accepted. Public/cloud URLs,
credentials in URLs, proxy paths and cloud model names are rejected in local mode.
Keep Ollama private: expose only the authenticated Waypoint backend to users.

Set `WAVY_AI_ENABLED=false` to disable model inference entirely while retaining workflow guidance.
The normal backend authentication/database environment is still required. The helper deliberately
does not generate a replacement JWT secret or enable an authentication bypass. Starting through
your existing IDE run configuration also works; the local Ollama defaults need no API key.

## Optional paid provider (explicit opt-in)

Set these variables on the **backend deployment**, then restart/redeploy the backend:

```text
WAVY_AI_ENABLED=true
WAVY_AI_PROVIDER=anthropic
WAVY_AI_API_KEY=<dedicated approved Anthropic API key>
WAVY_AI_MODEL=<model ID available to your Anthropic account>
```

Do not put the key in a `VITE_` variable, frontend build, repository, or chat message.
The document-extraction API key is deliberately not reused. Model IDs are configurable;
select a model available in your provider account rather than copying an old example.
The provider endpoint is fixed to `https://api.anthropic.com/v1/messages`.

Selecting Anthropic sends the current message, up to ten recent chat turns, maintained
product guidance, and minimal authorized tool results to Anthropic. Obtain your organization's
approval for that data processing and configure its provider account/retention accordingly.
Wavy does not persist conversations on the server or log prompts, tool results, or keys.
Provider failures log only the HTTP status or exception type, not response bodies.

## What works without an AI key

The existing conversational/workflow guide and API lookups remain available.
“Who is my HR?” is answered from the authenticated user's reporting assignment.
An assigned manager is described as HR only when their actual role is HR. A separate
HR assignment is not invented when the data model only supplies a reporting manager.
The header says “Workflow guide” rather than implying a model is connected.

## Live endpoints and tools

- `GET /api/myhr/wavy/capabilities`: authenticated configuration status, no credentials.
- `POST /api/myhr/wavy/chat`: message (maximum 2,000 characters), current route and bounded history.
- Read-only tools: allowlisted page navigation, own reporting assignment, authorized employee search, timesheet status
  summary, profile-request summary, payroll readiness, and maintained product help retrieval.
- The actor and organization come from the JWT and database, never the request body or model.
  Employee searches and payroll tools additionally enforce HR/Company Admin roles and delegate
  tenant/manager scoping to existing services. No writes, emails, submissions or approvals are exposed.
- Navigation actions are generated from successful server tools, not arbitrary model-provided URLs.

## Knowledge and limits

`WavyKnowledgeService` contains versioned product guidance derived from existing workflows.
It retrieves relevant passages by lexical overlap and supplies source IDs/titles to the UI.
This is a small retrieval-backed knowledge base, **not** a fine-tuned model or a complete
organization policy library. Add reviewed organization-scoped policy retrieval before promising
answers about benefits, leave, legal requirements or company-specific HR rules.

Each request allows up to three model calls (30-second local / 15-second Anthropic read timeout each), four read-only
tool calls and 700 output tokens per provider call. An in-memory per-user limit allows 12
requests/minute per backend instance. Use a shared gateway limit for multi-instance deployments.
Guidance mode is explicit when configuration, provider availability or request limits prevent AI replies.
AI answers may still be wrong; live facts must come from tool results and the UI displays sources.

## Deployment smoke checks

1. Without a JWT, chat must return 401. An employee must not gain admin access by changing
   the request role, route, history or tool arguments.
2. Confirm capabilities returns `aiAvailable: true` after configuring the provider.
3. Ask “Who is my HR?” with HR, admin-manager and missing-assignment profiles.
4. Ask a natural follow-up, then check your timesheet status. Verify the displayed source.
5. Ask an employee account to search another employee or query payroll readiness: it must
   receive no restricted records. Existing backend authorization remains authoritative.
6. Disable the key/provider and confirm the UI clearly falls back to workflow guidance.

API reference: https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview

Local API reference: https://docs.ollama.com/api/chat

## Tests

Normal unit tests use mock HTTP responses and never call a paid provider. Optional live local
smoke test (requires the installed model and running Ollama, uses synthetic test data only):

```powershell
cd backend
.\mvnw.cmd '-Dtest=WavyLocalModelSmokeTest' '-Dwavy.local.smoke=true' test
```

This checks real inference, a tool call, and a follow-up answer consuming the tool result.
