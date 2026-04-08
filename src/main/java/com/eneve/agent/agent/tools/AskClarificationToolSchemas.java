package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public final class AskClarificationToolSchemas {

    private AskClarificationToolSchemas() { }

    public static Tool askClarification() {
        return Tool.builder()
                .name("ask_clarification")
                .description("""
                        Ask the user one or more clarifying questions before proceeding with a task.

                        Use this tool ONLY when the user's request is genuinely ambiguous in a way that \
                        would cause you to produce a wrong or useless result if you guessed. Good examples:
                        - The user asks to "deploy" but hasn't specified which environment (production / staging).
                        - The user asks to "create a Jira ticket" but multiple projects are available and \
                        none is obvious from context.
                        - The user asks to "implement the feature" but scope (which service, which approach) \
                        is unclear.

                        Do NOT use this tool if:
                        - You can resolve the ambiguity by searching the knowledge base, reading the code, \
                        or making a reasonable assumption.
                        - The user is in Ask (read-only) mode — prefer searching and making assumptions there.
                        - You would be asking more than 3 questions (keep it focused).

                        Supported question types:
                        - "text"            — free-form text input
                        - "single_choice"   — user picks exactly one option
                        - "multiple_choice" — user picks one or more options
                        - "boolean"         — Yes / No

                        For "single_choice" and "multiple_choice" questions the "options" field is required.\
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("questions", JsonValue.from(Map.of(
                                        "type", "array",
                                        "description", "The list of questions to present to the user. Keep to 1-3 questions.",
                                        "minItems", 1,
                                        "maxItems", 3,
                                        "items", Map.of(
                                                "type", "object",
                                                "required", List.of("id", "question", "type"),
                                                "properties", Map.of(
                                                        "id", Map.of(
                                                                "type", "string",
                                                                "description", "Unique short identifier for this question, e.g. 'environment' or 'include_tests'"
                                                        ),
                                                        "question", Map.of(
                                                                "type", "string",
                                                                "description", "The question text shown to the user"
                                                        ),
                                                        "type", Map.of(
                                                                "type", "string",
                                                                "enum", List.of("text", "single_choice", "multiple_choice", "boolean"),
                                                                "description", "Determines how the UI renders the answer input"
                                                        ),
                                                        "options", Map.of(
                                                                "type", "array",
                                                                "items", Map.of("type", "string"),
                                                                "description", "Required for single_choice and multiple_choice questions"
                                                        )
                                                )
                                        )
                                )))
                                .build())
                        .addRequired("questions")
                        .build())
                .build();
    }
}
