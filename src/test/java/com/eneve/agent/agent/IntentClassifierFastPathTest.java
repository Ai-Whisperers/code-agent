package com.eneve.agent.agent;

import com.eneve.agent.agent.model.CommentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the fast-path classification logic in {@link IntentClassifier}.
 *
 * <p>Only the paths that short-circuit before reaching the Claude API are tested
 * here: null/blank input, the explicit {@code /fix} command, and the FIX_KEYWORDS
 * set added for common unambiguous phrases. The Claude-based fallback requires an
 * integration-level setup and is not covered here.
 */
class IntentClassifierFastPathTest {

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        // No AnthropicClient is injected — all tests below exit before the AI call.
        classifier = new IntentClassifier();
    }

    // ─── null / blank ─────────────────────────────────────────────────────────────

    @Test
    void nullMessage_returnsDiscuss() {
        assertEquals(CommentIntent.DISCUSS, classifier.classify(null, "some finding"));
    }

    @Test
    void blankMessage_returnsDiscuss() {
        assertEquals(CommentIntent.DISCUSS, classifier.classify("   ", "some finding"));
    }

    // ─── /fix command fast path ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/fix", "/Fix", "/FIX", "/fix this issue", "/fix please apply"})
    void fixCommand_returnsFix(String message) {
        assertEquals(CommentIntent.FIX, classifier.classify(message, "finding"),
                "Expected FIX for message: " + message);
    }

    // ─── FIX_KEYWORDS fast path ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "please fix",
            "apply this",
            "apply the fix",
            "go ahead",
            "do it",
            "yes fix",
            "fix it",
            "make the change",
            "apply suggestion"
    })
    void fixKeyword_exact_returnsFix(String keyword) {
        assertEquals(CommentIntent.FIX, classifier.classify(keyword, "finding"),
                "Expected FIX for keyword: " + keyword);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PLEASE FIX",
            "Apply This",
            "GO AHEAD",
            "Fix It",
            "YES FIX"
    })
    void fixKeyword_caseInsensitive_returnsFix(String message) {
        assertEquals(CommentIntent.FIX, classifier.classify(message, "finding"),
                "Expected FIX for case-variant: " + message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Sure, please fix the null check",
            "I think you should go ahead and apply this",
            "Ok go ahead, make the change",
            "Yes, please fix it as suggested"
    })
    void fixKeyword_embeddedInLongerMessage_returnsFix(String message) {
        assertEquals(CommentIntent.FIX, classifier.classify(message, "finding"),
                "Expected FIX for longer message: " + message);
    }
}
