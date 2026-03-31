package com.eneve.agent.jira;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stateless utility for converting Jira ADF (Atlassian Document Format) nodes
 * to plain text or Markdown.
 * Package-private — only used by collaborators within this package.
 */
@ApplicationScoped
class JiraAdfParser {

    // ── Plain text (used by AI prompt building) ───────────────────────────────

    /**
     * Recursively extract plain text from an ADF node.
     */
    String extractAdfText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.has("text")) return node.path("text").asText("");

        var content = node.path("content");
        if (content.isArray()) {
            var sb = new StringBuilder();
            for (var child : content) {
                String childType = child.path("type").asText("");
                String childText = extractAdfText(child);
                if (!childText.isEmpty()) {
                    if ("paragraph".equals(childType) || "heading".equals(childType)
                            || "bulletList".equals(childType) || "orderedList".equals(childType)) {
                        if (!sb.isEmpty()) sb.append("\n");
                    } else if ("listItem".equals(childType)) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append("- ");
                    }
                    sb.append(childText);
                }
            }
            return sb.toString();
        }
        return "";
    }

    // ── Markdown conversion (used for proposal editing) ───────────────────────

    /**
     * Convert an ADF document node to GitHub-Flavoured Markdown.
     * Supported: headings, paragraphs, bullet/ordered lists (nested),
     * code blocks, blockquotes, horizontal rules, hard breaks,
     * and inline marks: strong, em, code, link, strikethrough.
     */
    String adfToMarkdown(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        return blockToMd(node).stripTrailing();
    }

    private String blockToMd(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String type = node.path("type").asText("");

        return switch (type) {
            case "doc" -> {
                var sb = new StringBuilder();
                for (var child : node.path("content")) {
                    String block = blockToMd(child);
                    if (!block.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n\n");
                        sb.append(block);
                    }
                }
                yield sb.toString();
            }
            case "paragraph" -> inlinesToMd(node);
            case "heading" -> {
                int level = node.path("attrs").path("level").asInt(1);
                String hashes = "#".repeat(Math.min(6, Math.max(1, level)));
                yield hashes + " " + inlinesToMd(node);
            }
            case "bulletList" -> {
                var sb = new StringBuilder();
                for (var item : node.path("content")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append("- ").append(listItemToMd(item));
                }
                yield sb.toString();
            }
            case "orderedList" -> {
                var sb = new StringBuilder();
                int n = 1;
                for (var item : node.path("content")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(n++).append(". ").append(listItemToMd(item));
                }
                yield sb.toString();
            }
            case "listItem" -> listItemToMd(node);
            case "codeBlock" -> {
                String lang = node.path("attrs").path("language").asText("");
                String code = inlinesToMd(node);
                yield "```" + lang + "\n" + code + "\n```";
            }
            case "blockquote" -> {
                var sb = new StringBuilder();
                for (var child : node.path("content")) {
                    String block = blockToMd(child);
                    for (String line : block.split("\n", -1)) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append("> ").append(line);
                    }
                }
                yield sb.toString();
            }
            case "rule" -> "---";
            case "hardBreak" -> "\n";
            case "text" -> applyMarks(node.path("text").asText(""), node.path("marks"));
            case "inlineCard" -> node.path("attrs").path("url").asText("");
            default -> {
                // Leaf text node or unknown — fall through to inline content
                if (node.has("text")) yield node.path("text").asText("");
                var sb = new StringBuilder();
                for (var child : node.path("content")) sb.append(blockToMd(child));
                yield sb.toString();
            }
        };
    }

    /** Render all inline children of a block node to markdown. */
    private String inlinesToMd(JsonNode block) {
        var sb = new StringBuilder();
        for (var child : block.path("content")) sb.append(blockToMd(child));
        return sb.toString();
    }

    /** Render a listItem node: first child paragraph inline, remaining sub-blocks indented. */
    private String listItemToMd(JsonNode listItem) {
        var sb = new StringBuilder();
        boolean first = true;
        for (var child : listItem.path("content")) {
            String type = child.path("type").asText("");
            if (first && "paragraph".equals(type)) {
                sb.append(inlinesToMd(child));
                first = false;
            } else {
                // Sub-list or other block — indent with two spaces
                String sub = blockToMd(child);
                for (String line : sub.split("\n", -1)) {
                    sb.append("\n  ").append(line);
                }
            }
        }
        return sb.toString();
    }

    /** Apply ADF marks to a text string, returning decorated Markdown. */
    private String applyMarks(String text, JsonNode marks) {
        if (!marks.isArray() || marks.isEmpty()) return text;
        String result = text;
        for (var mark : marks) {
            String markType = mark.path("type").asText("");
            result = switch (markType) {
                case "strong"     -> "**" + result + "**";
                case "em"         -> "_" + result + "_";
                case "code"       -> "`" + result + "`";
                case "strike"     -> "~~" + result + "~~";
                case "link"       -> {
                    String href = mark.path("attrs").path("href").asText("");
                    yield href.isBlank() ? result : "[" + result + "](" + href + ")";
                }
                default -> result;
            };
        }
        return result;
    }

    /**
     * Extract both text AND href URLs from ADF nodes (needed to capture Aikido inline-card links).
     */
    String extractAdfTextAndLinks(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";

        var sb = new StringBuilder();
        if (node.has("text")) sb.append(node.path("text").asText(""));

        var marks = node.path("marks");
        if (marks.isArray()) {
            for (var mark : marks) {
                if ("link".equals(mark.path("type").asText(""))) {
                    String href = mark.path("attrs").path("href").asText("");
                    if (!href.isBlank()) sb.append(" ").append(href);
                }
            }
        }

        if ("inlineCard".equals(node.path("type").asText(""))) {
            String url = node.path("attrs").path("url").asText("");
            if (!url.isBlank()) sb.append(" ").append(url);
        }

        var content = node.path("content");
        if (content.isArray()) {
            for (var child : content) {
                sb.append(" ").append(extractAdfTextAndLinks(child));
            }
        }
        return sb.toString();
    }
}
