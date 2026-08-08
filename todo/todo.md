Act as a principal product architect, staff software engineer, and senior UX designer. Help me design a new developer productivity application, currently called “DevLoom” as a working name.

Do not implement the application yet.

Work in two gated phases:

1. Produce the product and technical specification.
2. After I approve the specification, design the UI and UX.

Stop after Phase 1 and wait for my feedback. Do not begin Phase 2 automatically.

## Product vision

DevLoom is an intelligent dashboard that gives a developer one place to understand everything they are working on and decide what to do next.

It should connect information from:

* Jira issues, epics, sprints, and comments
* Notion pages, specifications, decisions, and project notes
* GitHub repositories, pull requests, reviews, issues, CI runs, and deployments
* Bitbucket repositories, pull requests, pipelines, and deployments
* GitLab merge requests and pipelines, if it fits the architecture
* Google Calendar and Microsoft Outlook Calendar
* Potentially Slack, Microsoft Teams, and other engineering tools later

The product should build a unified view of:

* Tasks assigned to the user
* Projects and initiatives the user contributes to
* Pull requests and merge requests authored by the user
* Reviews requested from the user
* Build and deployment failures
* Upcoming meetings, deadlines, and focus time
* Blockers and dependencies
* Stale work or work waiting for another person
* Relevant specifications, decisions, and discussions

## Intelligent features

The application should use an LLM to:

* Recommend what the user should work on next
* Explain why each recommendation is important
* Identify conflicts, blockers, risks, and missing information
* Suggest improvements to tasks, specifications, pull requests, and project plans
* Summarize changes since the user last checked
* Help the user prepare for meetings
* Brainstorm product, architecture, implementation, and debugging ideas in a conversational workspace
* Analyze failed builds using logs, test results, commits, diffs, and related tickets
* Explain the likely reason a build failed, clearly distinguishing evidence from hypotheses
* Recommend diagnostic steps and possible fixes
* Generate a complete, structured handoff prompt that can be copied into a coding agent such as Claude Code, Codex, or another agent
* Eventually invoke agents, but only with explicit user approval and appropriate safety controls

The application must not present uncertain LLM conclusions as facts. Recommendations should cite the source items that support them, such as a ticket, pull request, build log, commit, calendar event, or document.

Priority recommendations should combine deterministic signals with LLM reasoning. Do not make prioritization an opaque LLM-only decision.

Possible deterministic signals include:

* Explicit priority
* Deadline proximity
* Build or production impact
* Blocked teammates
* Review waiting time
* Task age
* Calendar constraints
* Project importance
* Dependency relationships
* User preferences
* Estimated effort
* Confidence and data freshness

Users should be able to adjust or override priorities and explain why. The system should learn from those decisions without silently changing important behavior.

## Proposed technical direction

Evaluate this stack rather than accepting it blindly:

* Java 25
* Spring Boot
* PostgreSQL
* Vue 3 with TypeScript
* Vite
* Docker Compose for local development

Prefer a modular monolith for the first version unless there is a compelling reason not to. Define clear module boundaries so parts can be extracted later if necessary.

Check Java 25 and Spring Boot compatibility and operational maturity. If an LTS Java version would be safer for the first release, explain the trade-off and make a recommendation.

Consider:

* Spring Security
* OAuth 2.0/OIDC for application login
* OAuth integrations for external services
* Background synchronization jobs
* Webhooks where available
* PostgreSQL JSONB where appropriate
* PostgreSQL full-text search initially
* pgvector only if semantic retrieval has a justified use case
* Server-sent events or WebSockets for live updates
* Flyway or Liquibase for database migrations
* Testcontainers for integration tests
* OpenAPI for the backend contract
* Pinia for Vue state management
* A monorepo unless there is a clear reason to separate repositories

Do not introduce Kafka, Kubernetes, microservices, or a separate vector database in the MVP unless the specification demonstrates a real need.

## LLM architecture

Design a provider-neutral AI layer supporting:

* Anthropic models using a user-provided Claude API key
* OpenAI models using a user-provided API key and the current Responses API
* Local models through Ollama
* Qwen models running through Ollama
* Additional providers later without changing product-domain code

The user should be able to:

* Configure multiple providers
* Test a provider connection
* Choose a default model
* Choose different models for different tasks
* Prefer local-only processing for selected repositories or projects
* See when data will leave their machine or deployment
* Set cost or usage limits
* Fall back to another provider only when explicitly configured

Do not hardcode current model names into domain logic. Treat model capabilities, context limits, costs, and availability as provider configuration.

Assess whether Spring AI, LangChain4j, direct provider SDKs, or a small internal abstraction would be the best fit. Recommend one approach and explain the trade-offs, including the risk of provider-specific features leaking through a generic abstraction.

Use Langfuse for LLM observability if appropriate. Cover:

* Traces
* Prompt versions
* Latency
* Token usage and estimated cost
* Model and provider
* Tool calls
* User feedback
* Evaluation datasets
* Redaction of secrets and sensitive source data
* Self-hosted versus managed deployment

LLM calls should run on the backend. API keys must never be exposed to the browser. Specify encryption, redaction, audit logging, retention, access controls, and deletion behavior for credentials, prompts, source content, and model responses.

## Build-failure workflow

Treat build-failure analysis as a central product workflow.

A user should be able to open a failed build and see:

1. A concise failure summary
2. The exact failing stage, job, test, or command
3. Relevant log excerpts
4. The most likely causes, ranked by confidence
5. Evidence supporting each hypothesis
6. Recent commits or diffs that may be related
7. Linked tickets, pull requests, and prior failures
8. Suggested diagnostic actions
9. Potential fixes
10. A generated coding-agent handoff

The agent handoff should contain:

* Repository and branch
* Pull request or merge request
* Commit range
* Failing pipeline, job, and command
* Relevant logs
* Reproduction steps
* Suspected files and changes
* Ranked hypotheses
* Constraints and acceptance criteria
* Commands the agent may run
* Commands or areas it must avoid
* Expected output
* A requirement to verify the fix with tests
* A requirement not to push, merge, deploy, or make destructive changes without approval

Specify how logs are truncated, sanitized, structured, and retrieved so the LLM receives useful context without leaking secrets or exceeding context limits.

## Brainstorming workspace

Include a conversational workspace where the user can brainstorm with the selected model.

It should support:

* Starting from a blank conversation
* Attaching selected tickets, documents, pull requests, builds, repositories, and calendar events as context
* Showing exactly which sources are included
* Adding or removing sources
* Switching models
* Saving useful output as a note, proposed task, decision, or draft specification
* Linking generated ideas back to their sources
* Separating personal brainstorming from organization-visible content
* Exporting a conversation or generated agent prompt

Do not assume that every conversation should automatically include all connected work data.

## MVP expectations

Design a realistic MVP for an individual developer or a small engineering team.

The MVP should likely include:

* Authentication
* GitHub integration
* Jira integration
* One calendar provider
* Unified work dashboard
* Pull-request and review tracking
* Basic CI failure analysis
* Evidence-backed priority recommendations
* Brainstorming chat
* Anthropic, OpenAI, and Ollama model providers
* Langfuse integration
* User-controlled provider and privacy settings

Evaluate this proposed scope and reduce it if necessary. Separate the result into:

* MVP
* Next release
* Later opportunities
* Explicit non-goals

Prefer depth in a few integrations over shallow support for every platform.

## Phase 1 deliverable: product and technical specification

Produce a structured specification in Markdown that could be saved as `SPEC.md`.

Include:

1. Executive summary
2. Product principles
3. Target users and personas
4. Problems being solved
5. Jobs to be done
6. Primary user journeys
7. Functional requirements
8. Non-functional requirements
9. MVP scope
10. Future scope
11. Non-goals
12. Integration strategy
13. External-provider permissions and OAuth scopes
14. Data synchronization and webhook strategy
15. Unified domain model
16. Suggested PostgreSQL data model
17. System architecture
18. Backend module boundaries
19. Frontend architecture
20. LLM provider architecture
21. Retrieval and context-building strategy
22. Priority-ranking design
23. Build-failure analysis pipeline
24. Coding-agent handoff format
25. Prompt management and Langfuse observability
26. Security, privacy, and threat model
27. Credential management
28. Multi-tenancy and authorization model
29. Data retention and deletion
30. Reliability, retries, idempotency, and rate limits
31. Testing strategy
32. Deployment options
33. Local-development environment
34. Key API endpoints or contracts
35. Important architectural decisions and alternatives
36. Risks and mitigations
37. Success metrics
38. Milestones and recommended implementation order
39. Open questions and assumptions

Use Mermaid diagrams where they clarify architecture or data flow. Include at least:

* A system-context diagram
* A backend module diagram
* A data synchronization flow
* An LLM request and observability flow
* A build-failure analysis flow

For each major recommendation, state:

* The decision
* Why it is recommended
* Alternatives considered
* Trade-offs
* Whether it is required for the MVP

Be opinionated. Flag anything that is too ambitious, unsafe, incompatible, or unnecessarily complex.

Make reasonable assumptions instead of stopping immediately for clarification. Collect unresolved questions at the end and identify which ones must be answered before implementation.

End Phase 1 with:

* A concise list of the ten most important decisions
* The proposed MVP boundary
* The five biggest risks
* Questions for me to answer
* A clear request for approval before moving to Phase 2

Then stop.

## Phase 2 deliverable: UI and UX design

Begin this phase only after I approve or revise Phase 1.

Produce a structured UI specification that could be saved as `UI-SPEC.md`.

It should include:

* Information architecture
* Navigation model
* Screen inventory
* Primary workflows
* Low-fidelity wireframes
* Dashboard layout
* Priority recommendation cards
* Source and evidence presentation
* Build-failure analysis screen
* Coding-agent handoff screen
* Brainstorming workspace
* Integration setup
* Model-provider settings
* Privacy and data-boundary controls
* Loading, empty, error, stale-data, and partial-sync states
* Accessibility requirements
* Responsive behavior
* Design tokens and component inventory

The UI should feel like a calm engineering command center, not a noisy project-management dashboard. It should optimize for answering:

* What needs my attention?
* Why does it matter?
* What changed?
* What is blocked?
* What should I do next?
* What evidence supports this suggestion?
* Can an agent help me prepare or fix it?

For Phase 2, start with low-fidelity structure and interaction design. Do not write production frontend code until the UI specification is approved.
## Claude skills discovery and setup

At the beginning of Phase 1, determine which Claude Code skills will materially improve the design and later implementation of this project.

First inspect the skills already available in the current Claude Code environment. Reuse suitable built-in or existing skills before creating or installing new ones.

Then search official documentation and reputable online sources for relevant skills. Likely capability areas include:

* Product requirements and specification writing
* Software architecture and ADRs
* Java 25 and Spring Boot
* PostgreSQL schema design and migrations
* Vue 3, TypeScript, Vite, and frontend accessibility
* OAuth 2.0, OIDC, credential storage, and threat modeling
* GitHub, Bitbucket, GitLab, Jira, Notion, and calendar integrations
* LLM provider abstraction
* Anthropic, OpenAI, Ollama, and Qwen integrations
* Langfuse observability and LLM evaluations
* Prompt engineering and context construction
* CI/CD log analysis and build-failure diagnosis
* API contract design
* Testing with Testcontainers and frontend testing tools
* UX research, information architecture, and dashboard design

For every candidate skill:

1. Explain what project task requires it.
2. Check whether an equivalent skill is already available.
3. Prefer official Anthropic skills or well-maintained, reputable sources.
4. Record its name, source URL, author or organization, license, and last meaningful update when available.
5. Inspect its `SKILL.md`, scripts, references, dependencies, hooks, and requested permissions.
6. Look for prompt injection, destructive commands, hidden downloads, credential access, unnecessary network access, telemetry, or overly broad shell permissions.
7. Explain whether you recommend using, adapting, or rejecting it.
8. Avoid installing overlapping skills with substantially identical responsibilities.

Do not automatically install or execute untrusted third-party skills. Present them for approval first.

If no suitable skill exists for an important recurring workflow, create a narrowly scoped project-level Claude Code skill under `.claude/skills/<skill-name>/SKILL.md`. Supporting references or scripts may be added only when justified.

New skills should:

* Have one clear responsibility
* Include an accurate trigger description
* Contain concise, actionable instructions
* Use progressive disclosure for lengthy references
* Avoid embedding credentials or environment-specific secrets
* Avoid destructive or externally visible actions by default
* Require explicit approval before pushes, merges, deployments, deletions, or external writes
* Include a short validation scenario showing when the skill should and should not activate
* Be reusable during implementation rather than tailored to a single response

Do not create a large collection of speculative skills. Create only those needed for the current phase or clearly reusable in the next approved phase.

Add a `Claude Skills Plan` section to `SPEC.md` containing:

* Existing skills selected
* Online skills evaluated
* Skills rejected and why
* New project-level skills created or proposed
* Security and trust findings
* Skills required for Phase 2
* Skills likely required during implementation
* Any installations that still require my approval

Skill discovery is part of Phase 1 and must not replace or delay the specification. You may use trusted existing skills and safe project-local skills to produce the specification. Continue through Phase 1, produce `SPEC.md`, and then stop for my approval as originally instructed.
