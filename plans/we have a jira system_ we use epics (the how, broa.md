
# we have a jira system: we use epics (the how, broad scope describing ideas and a vision: a business case), features (what: I want screen to show projects with an export to csv) and user stories (how) to design and build software. The product owners are responsible for epics and feature. The epics are mapped to sprints.

I have AI with an API to Jira and want to review readiness for epics and features. Make me prompt for reviewing epics and a prompt for reviewing features.

That’s a great use case — having AI review the *readiness* of epics and features before they move downstream can create huge consistency and clarity benefits across teams. Below are two optimized prompts: one for **epics** and one for **features**, both tuned for a Jira-integrated AI context.

***

### Prompt for Reviewing Epics

> You are a Jira review assistant evaluating the readiness of a **Jira Epic** for planning and development.
> The Epic represents a **broad business case or idea** that defines *why* and *what value or outcome* is expected, not technical details.
>
> Review the Epic below for clarity, alignment, feasibility, and readiness.
>
> **Epic Review Checklist:**
> 1. **Vision and goal:** Is there a clear purpose or business outcome? Does it explain *why* this matters for the product or users?
> 2. **Scope and boundaries:** Are the problem space and intended solution area well defined? Are there clear exclusions or constraints?
> 3. **Value and success:** Does it state measurable success criteria or acceptance indicators?
> 4. **Dependencies:** Are upstream or cross-team dependencies identified?
> 5. **Fit for planning:** Could a team derive Features from this Epic, with a reasonable understanding of effort and sequencing?
> 6. **Language and structure:** Is the writing clear, concise, and free of jargon? Are Jira fields (summary, description, acceptance, etc.) applied correctly?
>
> Return a **readiness score from 0–100** and a short, structured **improvement summary** listing concrete recommendations for making this Epic fully ready for sprint mapping.
>
> **Epic input (from Jira API):**
> [Insert the JSON payload or fields: “summary,” “description,” “acceptance criteria,” “linked issues,” etc.]

***

### Prompt for Reviewing Features

> You are a Jira review assistant evaluating a **Feature** that defines *what* should be built — typically a user-facing capability that delivers value within an Epic.
>
> Review the Feature for completeness, testability, and development readiness.
>
> **Feature Review Checklist:**
> 1. **User value and intent:** Does the Feature clearly state *what* users can do and *why* it matters?
> 2. **Functional clarity:** Are key behaviors, workflows, or UI outcomes described precisely?
> 3. **Acceptance criteria:** Are they clearly written, testable, and outcome-focused (not implementation-specific)?
> 4. **Dependencies and fit:** Is the Feature traceable back to a parent Epic and other linked items? Does it align with the Epic’s goals?
> 5. **Feasibility and size:** Is the scope right for a sprint or for decomposition into user stories?
> 6. **Non-functional aspects:** Does it mention performance, security, or compliance implications if relevant?
> 7. **Readability and structure:** Is the text unambiguous and well organized for team handover?
>
> Return a **readiness score from 0–100** and a structured **summary of improvement actions** indicating what to clarify, tighten, or reformat before implementation.
>
> **Feature input (from Jira API):**
> [Insert “summary,” “description,” “acceptance criteria,” “linked Epic,” “dependencies,” etc.]

***

Would you like me to tailor these prompts further for a **specific AI model style** (for instance, conversational like ChatGPT or more JSON-evaluable for automation pipelines)?

---

# We use claude sonnet we want json response so we can generate reports. The features should include a test if we have user stories and if the user stories are complete. Lets add a prompt for a user story review. User stories are written by delivery team (dev/engineers).

You can drive this with Claude’s JSON mode by giving it a clear schema and rubric per artifact type. Below are three prompts (Epic, Feature, User Story) designed for Sonnet, all returning strict JSON and including story-completeness checks on Features.

***

## Epic readiness review prompt

Use this as the *system* (or a fixed “instruction”) message for Epic reviews:

```text
You are an assistant that reviews Jira Epics for readiness and ALWAYS responds with STRICT, VALID JSON only.

You are evaluating:
- Epics = broad business cases that describe the WHY, the vision, expected value, high-level scope and constraints, not detailed implementation.

Use this readiness rubric:
- 0–39 = Poor (insufficient for planning)
- 40–69 = Needs refinement
- 70–89 = Ready with minor improvements
- 90–100 = Fully ready

Check:
1) Vision & goal clarity (why this matters, business outcome).
2) Scope & boundaries (problem space, in-scope, out-of-scope or constraints).
3) Value & success (business value, success criteria or outcome indicators).
4) Dependencies & alignment (cross-team / external dependencies, alignment with product/strategy).
5) Decomposition readiness (is it clear enough to derive Features and estimate at a reasonable level).
6) Language & structure (clarity, absence of contradictions, correct use of fields).

Your response MUST be valid JSON with this exact schema:

{
  "readiness_score": number,           // 0-100
  "readiness_label": string,           // "poor" | "needs_refinement" | "ready_with_minor_improvements" | "fully_ready"
  "summary": string,                   // short 1-3 sentence summary of overall assessment
  "checks": {
    "vision_and_goal": {
      "score": number,                 // 0-100
      "status": string,                // "ok" | "weak" | "missing"
      "comment": string
    },
    "scope_and_boundaries": {
      "score": number,
      "status": string,
      "comment": string
    },
    "value_and_success": {
      "score": number,
      "status": string,
      "comment": string
    },
    "dependencies_and_alignment": {
      "score": number,
      "status": string,
      "comment": string
    },
    "decomposition_readiness": {
      "score": number,
      "status": string,
      "comment": string
    },
    "language_and_structure": {
      "score": number,
      "status": string,
      "comment": string
    }
  },
  "key_risks": [string],               // empty array if none
  "blocking_gaps": [string],           // items that MUST be addressed before planning
  "recommended_improvements": [        // concrete actions
    {
      "area": string,                  // e.g. "scope_and_boundaries"
      "description": string,
      "suggested_owner_role": string   // e.g. "Product Owner", "Architect"
    }
  ]
}

Do not include any text outside the JSON object.
```

Then your *user* message can be just:

```text
Review the following Jira Epic for readiness using the JSON schema:

<JIRA_EPIC_JSON_OR_FIELDS_HERE>
```


***

## Feature readiness review prompt (with user story checks)

Use this as the Feature reviewer; note the explicit user-story presence/completeness checks:

```text
You are an assistant that reviews Jira Features for readiness and ALWAYS responds with STRICT, VALID JSON only.

Context:
- Features = the WHAT: user-facing capabilities within an Epic, describing behavior and outcomes.
- User Stories = HOW: implementation-level slices created by the delivery team.

Use this readiness rubric:
- 0–39 = Poor (not ready)
- 40–69 = Needs refinement
- 70–89 = Ready with minor improvements
- 90–100 = Fully ready

Evaluate:
1) User value & intent (what users can do and why it matters).
2) Functional clarity (key behaviors, flows, UI outcomes).
3) Acceptance criteria quality (clear, testable, outcome-focused).
4) Alignment & traceability (linked Epic, consistent with Epic goal).
5) Feasibility & size (appropriate for 1–2 sprints, can be decomposed into stories).
6) Non-functional aspects (performance, security, compliance etc., when relevant).
7) Linked user stories:
   - presence of user stories for this Feature;
   - completeness and quality of those stories at a high level (title, description, acceptance criteria).

Your response MUST be valid JSON with this exact schema:

{
  "readiness_score": number,              // 0-100
  "readiness_label": string,              // "poor" | "needs_refinement" | "ready_with_minor_improvements" | "fully_ready"
  "summary": string,
  "checks": {
    "user_value_and_intent": {
      "score": number,
      "status": string,                   // "ok" | "weak" | "missing"
      "comment": string
    },
    "functional_clarity": {
      "score": number,
      "status": string,
      "comment": string
    },
    "acceptance_criteria": {
      "score": number,
      "status": string,
      "comment": string
    },
    "alignment_and_traceability": {
      "score": number,
      "status": string,
      "comment": string
    },
    "feasibility_and_size": {
      "score": number,
      "status": string,
      "comment": string
    },
    "non_functional_aspects": {
      "score": number,
      "status": string,
      "comment": string
    },
    "user_stories": {
      "stories_present": boolean,
      "overall_quality_score": number,    // 0-100, aggregated
      "status": string,                   // "ok" | "weak" | "missing"
      "comment": string
    }
  },
  "user_story_summary": {
    "total_stories": number,
    "stories_evaluated": number,
    "incomplete_stories": number,        // missing key fields (e.g. description or acceptance criteria)
    "examples_of_incomplete_story_keys": [string]  // e.g. Jira keys or IDs
  },
  "key_risks": [string],
  "blocking_gaps": [string],
  "recommended_improvements": [
    {
      "area": string,                     // e.g. "acceptance_criteria", "user_stories"
      "description": string,
      "suggested_owner_role": string      // e.g. "Product Owner", "Delivery Team"
    }
  ]
}

Do not include any text outside the JSON object.
```

User message:

```text
Review the following Jira Feature and its linked user stories for readiness using the JSON schema:

{
  "feature": { ... },              // summary, description, acceptance criteria, epic link, etc.
  "user_stories": [ { ... }, ... ] // array of linked story objects from Jira
}
```


***

## User story readiness review prompt

For the delivery-team-written stories, you can use:

```text
You are an assistant that reviews Jira User Stories for readiness and ALWAYS responds with STRICT, VALID JSON only.

Context:
- User Stories are written by the delivery team (engineers) and must be implementation-ready.
- A good story is clear, testable, and feasible within a sprint.

Use this readiness rubric:
- 0–39 = Poor (not ready for sprint)
- 40–69 = Needs refinement
- 70–89 = Ready with minor improvements
- 90–100 = Fully ready

Evaluate:
1) Story format & intent (clear user-centric or system behavior statement; if a template is used like “As a…, I want…, so that…”, check quality, not just presence).
2) Functional clarity (what exactly needs to be built or changed).
3) Acceptance criteria (clear, testable, outcome-based, supports QA & automation).
4) Dependencies & assumptions (identified and understandable).
5) Size & feasibility (doable within a sprint, can be estimated).
6) Technical notes (relevant implementation hints, constraints, or risks, without over-specifying).

Your response MUST be valid JSON with this exact schema:

{
  "readiness_score": number,              // 0-100
  "readiness_label": string,              // "poor" | "needs_refinement" | "ready_with_minor_improvements" | "fully_ready"
  "summary": string,
  "checks": {
    "story_intent_and_format": {
      "score": number,
      "status": string,                   // "ok" | "weak" | "missing"
      "comment": string
    },
    "functional_clarity": {
      "score": number,
      "status": string,
      "comment": string
    },
    "acceptance_criteria": {
      "score": number,
      "status": string,
      "comment": string
    },
    "dependencies_and_assumptions": {
      "score": number,
      "status": string,
      "comment": string
    },
    "size_and_feasibility": {
      "score": number,
      "status": string,
      "comment": string
    },
    "technical_notes": {
      "score": number,
      "status": string,
      "comment": string
    }
  },
  "blocking_gaps": [string],
  "recommended_improvements": [
    {
      "area": string,                     // e.g. "acceptance_criteria"
      "description": string,
      "suggested_owner_role": string      // e.g. "Delivery Team", "QA", "Product Owner"
    }
  ]
}

Do not include any text outside the JSON object.
```

User message:

```text
Review the following Jira User Story for readiness using the JSON schema:

<JIRA_USER_STORY_JSON_OR_FIELDS_HERE>
```


***

To make this really effective, the next key choice is: do you want one **unified** JSON schema across epics/features/stories (for simpler reporting), or are you happy with 3 distinct but related schemas as above?
<span style="display:none">[^2_1][^2_10][^2_11][^2_12][^2_13][^2_14][^2_15][^2_2][^2_3][^2_4][^2_5][^2_6][^2_7][^2_8][^2_9]</span>

<div align="center">⁂</div>

[^2_1]: https://agilemania.com/epic-vs-feature-vs-user-story

[^2_2]: https://www.atlassian.com/agile/project-management/epics-stories-themes

[^2_3]: https://dl.acm.org/doi/full/10.1145/3729176.3729200

[^2_4]: https://www.mountaingoatsoftware.com/blog/stories-epics-and-themes

[^2_5]: https://www.rosemet.com/epics-features-and-user-stories/

[^2_6]: https://www.wrike.com/agile-guide/faq/what-is-definition-of-ready/

[^2_7]: https://www.tribe.ai/applied-ai/a-gentle-introduction-to-structured-generation-with-anthropic-api

[^2_8]: https://rafayethossain.github.io/2022-11-15-A-Beginners-Guide-to-Writing-SMART-User-Story/

[^2_9]: https://mahedee.net/structuring-agile-work-epics-stories-artifacts/

[^2_10]: https://baz.co/resources/how-to-achieve-structured-output-in-claude-3-7-three-practical-approaches

[^2_11]: https://agilesparks.com/how-to-improve-user-stories-readiness-and-maturity-so-that-the-team-can-complete-them-quickly-inside-a-sprint/

[^2_12]: https://www.atlassian.com/agile/project-management/definition-of-ready

[^2_13]: https://platform.claude.com/docs/en/build-with-claude/structured-outputs

[^2_14]: https://www.scribd.com/document/1003004457/Epic

[^2_15]: https://www.linkedin.com/posts/work-a48b82309_definition-of-ready-dor-dor-ensures-activity-7375487182427344896-kpFX

