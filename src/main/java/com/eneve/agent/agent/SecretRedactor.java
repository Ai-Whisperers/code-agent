package com.eneve.agent.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Scrubs well-known secret patterns from arbitrary text before the text is
 * embedded and stored in the knowledge index.
 *
 * <p>Each {@link Rule} replaces only the sensitive value (not the surrounding
 * key name or punctuation) with the literal token {@code [REDACTED]}, so the
 * resulting text still reads naturally for embedding purposes.
 *
 * <p>All methods are static; no instantiation is needed.
 */
public final class SecretRedactor {

    static final String REDACTED = "[REDACTED]";

    private SecretRedactor() {}

    private record Rule(Pattern pattern, int valueGroup) {}

    /**
     * Key=value patterns where the value is the secret.
     * Group 1 captures the key+separator, group 2 captures the value.
     */
    private static final List<Rule> RULES = List.of(

            // password / passwd / pwd  =  <value>
            new Rule(Pattern.compile(
                    "(?i)((?:password|passwd|pwd)\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // secret / client_secret / client-secret  =  <value>
            new Rule(Pattern.compile(
                    "(?i)((?:client[_-]?)?secret\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // token / auth_token / access_token / id_token / refresh_token  =  <value>
            new Rule(Pattern.compile(
                    "(?i)((?:auth[_-]?|access[_-]?|id[_-]?|refresh[_-]?)?token\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // api_key / api-key / apikey  =  <value>
            new Rule(Pattern.compile(
                    "(?i)(api[_-]?key\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // access_key / access-key  =  <value>
            new Rule(Pattern.compile(
                    "(?i)(access[_-]?key\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // private_key / private-key  =  <value>
            new Rule(Pattern.compile(
                    "(?i)(private[_-]?key\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // credential / credentials  =  <value>
            new Rule(Pattern.compile(
                    "(?i)(credentials?\\s*[=:]\\s*)([^\\s\"'`,;]+)",
                    Pattern.MULTILINE), 2),

            // Bearer <token>  (HTTP Authorization header values in logs/docs)
            // Must appear BEFORE the generic "authorization" key=value rule so the
            // keyword "Bearer" is still present when this pattern fires.
            new Rule(Pattern.compile(
                    "(?i)(Bearer\\s+)([A-Za-z0-9\\-._~+/]+=*)",
                    Pattern.MULTILINE), 2),

            // authorization  =  <value>  (e.g. in config files)
            // Captures the entire rest of the line so multi-word values (e.g. "Basic XYZ")
            // are fully redacted rather than just the first token.
            new Rule(Pattern.compile(
                    "(?i)(authorization\\s*[=:]\\s*)([^\\n]+)",
                    Pattern.MULTILINE), 2),

            // AWS Access Key ID:  AKIA followed by 16 uppercase alphanum chars
            new Rule(Pattern.compile(
                    "(AKIA[0-9A-Z]{16})"), 1),

            // URLs with embedded credentials:  scheme://user:pass@host
            new Rule(Pattern.compile(
                    "([a-zA-Z][a-zA-Z0-9+\\-.]*://[^:@\\s]+:)([^@\\s]+)(@)"), 2),

            // PEM private key blocks (entire block including headers)
            new Rule(Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----",
                    Pattern.CASE_INSENSITIVE), 1)
    );

    /**
     * Returns a copy of {@code text} with all recognised secret patterns
     * replaced by {@value #REDACTED}. Returns the original string unchanged
     * if {@code text} is {@code null} or blank.
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(result);
            if (rule.valueGroup() == 1) {
                // Whole-match replacement (AWS key, PEM block)
                result = matcher.replaceAll(REDACTED);
            } else {
                // Replace only the value capture group, preserving key+separator
                result = matcher.replaceAll(m -> {
                    StringBuilder sb = new StringBuilder();
                    for (int g = 1; g <= m.groupCount(); g++) {
                        sb.append(g == rule.valueGroup() ? REDACTED : m.group(g));
                    }
                    return sb.toString();
                });
            }
        }
        return result;
    }
}
