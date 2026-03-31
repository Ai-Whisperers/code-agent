package com.eneve.agent.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts GitHub-Flavoured Markdown to Atlassian Document Format (ADF) JSON.
 * The resulting ADF can be sent directly to the Jira REST API v3.
 *
 * Supported Markdown:
 *   Blocks:  headings (#–######), fenced code blocks, blockquotes, horizontal rules,
 *            bullet lists (- / * / +), ordered lists (1.), paragraphs
 *   Inlines: **bold**, _italic_, `code`, ~~strikethrough~~, [link](url)
 *
 * Package-private — all access goes through {@link JiraService}.
 */
@ApplicationScoped
class AdfBuilder {

    // ── Inline patterns ───────────────────────────────────────────────────────
    // Processed in order: links, code spans, bold, italic, strikethrough.
    private static final Pattern LINK       = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern CODE_SPAN  = Pattern.compile("(?<!`)`([^`]+)`(?!`)");
    private static final Pattern BOLD       = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC     = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)");
    private static final Pattern STRIKE     = Pattern.compile("~~(.+?)~~");

    @Inject ObjectMapper mapper;

    /**
     * Convert a Markdown string to an ADF document node.
     * Returns a minimal single-paragraph document for null/blank input.
     */
    ObjectNode markdownToAdf(String markdown) {
        var doc  = mapper.createObjectNode();
        doc.put("type",    "doc");
        doc.put("version", 1);
        var content = doc.putArray("content");

        if (markdown == null || markdown.isBlank()) {
            content.addObject().put("type", "paragraph").putArray("content");
            return doc;
        }

        parseBlocks(markdown.stripTrailing(), content);
        if (content.isEmpty()) {
            content.addObject().put("type", "paragraph").putArray("content");
        }
        return doc;
    }

    // ── Block parsing ─────────────────────────────────────────────────────────

    private void parseBlocks(String text, ArrayNode out) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];

            // ── Fenced code block ────────────────────────────────────────────
            if (raw.startsWith("```")) {
                String lang = raw.substring(3).trim();
                var sb = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].startsWith("```")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(lines[i]);
                    i++;
                }
                var block = out.addObject();
                block.put("type", "codeBlock");
                if (!lang.isBlank()) block.putObject("attrs").put("language", lang);
                var c = block.putArray("content");
                c.addObject().put("type", "text").put("text", sb.toString());
                i++; // skip closing ```
                continue;
            }

            // ── ATX heading (#...) ───────────────────────────────────────────
            Matcher hm = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(raw);
            if (hm.matches()) {
                var block = out.addObject();
                block.put("type", "heading");
                block.putObject("attrs").put("level", hm.group(1).length());
                addInline(block.putArray("content"), hm.group(2));
                i++;
                continue;
            }

            // ── Horizontal rule ──────────────────────────────────────────────
            if (raw.matches("^[-*_]{3,}\\s*$")) {
                out.addObject().put("type", "rule");
                i++;
                continue;
            }

            // ── Blockquote ───────────────────────────────────────────────────
            if (raw.startsWith("> ") || raw.equals(">")) {
                var sb = new StringBuilder();
                while (i < lines.length && (lines[i].startsWith("> ") || lines[i].equals(">"))) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(lines[i].startsWith("> ") ? lines[i].substring(2) : "");
                    i++;
                }
                var block = out.addObject();
                block.put("type", "blockquote");
                parseBlocks(sb.toString(), block.putArray("content"));
                continue;
            }

            // ── Bullet list ──────────────────────────────────────────────────
            if (raw.matches("^[-*+]\\s+.*")) {
                var block = out.addObject();
                block.put("type", "bulletList");
                var listContent = block.putArray("content");
                while (i < lines.length && lines[i].matches("^[-*+]\\s+.*")) {
                    String itemText = lines[i].replaceFirst("^[-*+]\\s+", "");
                    addListItem(listContent, itemText);
                    i++;
                }
                continue;
            }

            // ── Ordered list ─────────────────────────────────────────────────
            if (raw.matches("^\\d+\\.\\s+.*")) {
                var block = out.addObject();
                block.put("type", "orderedList");
                var listContent = block.putArray("content");
                while (i < lines.length && lines[i].matches("^\\d+\\.\\s+.*")) {
                    String itemText = lines[i].replaceFirst("^\\d+\\.\\s+", "");
                    addListItem(listContent, itemText);
                    i++;
                }
                continue;
            }

            // ── Blank line ───────────────────────────────────────────────────
            if (raw.isBlank()) {
                i++;
                continue;
            }

            // ── Paragraph (collect consecutive non-special lines) ────────────
            var sb = new StringBuilder();
            while (i < lines.length) {
                String l = lines[i];
                if (l.isBlank()
                        || l.startsWith("#")
                        || l.startsWith("```")
                        || l.startsWith("> ")
                        || l.equals(">")
                        || l.matches("^[-*+]\\s+.*")
                        || l.matches("^\\d+\\.\\s+.*")
                        || l.matches("^[-*_]{3,}\\s*$")) {
                    break;
                }
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(l);
                i++;
            }
            if (!sb.isEmpty()) {
                var block = out.addObject();
                block.put("type", "paragraph");
                addInline(block.putArray("content"), sb.toString());
            }
        }
    }

    private void addListItem(ArrayNode listContent, String text) {
        var item = listContent.addObject();
        item.put("type", "listItem");
        var para = item.putArray("content").addObject();
        para.put("type", "paragraph");
        addInline(para.putArray("content"), text);
    }

    // ── Inline parsing ────────────────────────────────────────────────────────

    /**
     * Parse inline Markdown in {@code text} and append ADF text nodes to {@code out}.
     * Processing order: links → code spans → bold → italic → strikethrough → plain.
     */
    void addInline(ArrayNode out, String text) {
        if (text == null || text.isEmpty()) return;
        addInlineLinks(out, text);
    }

    private void addInlineLinks(ArrayNode out, String text) {
        Matcher m = LINK.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addInlineCode(out, text.substring(last, m.start()));
            var node = out.addObject();
            node.put("type", "text");
            node.put("text", m.group(1));
            node.putArray("marks").addObject()
                    .put("type", "link")
                    .putObject("attrs").put("href", m.group(2));
            last = m.end();
        }
        if (last < text.length()) addInlineCode(out, text.substring(last));
    }

    private void addInlineCode(ArrayNode out, String text) {
        Matcher m = CODE_SPAN.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addInlineBold(out, text.substring(last, m.start()));
            var node = out.addObject();
            node.put("type", "text");
            node.put("text", m.group(1));
            node.putArray("marks").addObject().put("type", "code");
            last = m.end();
        }
        if (last < text.length()) addInlineBold(out, text.substring(last));
    }

    private void addInlineBold(ArrayNode out, String text) {
        Matcher m = BOLD.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addInlineItalic(out, text.substring(last, m.start()));
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            var node = out.addObject();
            node.put("type", "text");
            node.put("text", inner);
            node.putArray("marks").addObject().put("type", "strong");
            last = m.end();
        }
        if (last < text.length()) addInlineItalic(out, text.substring(last));
    }

    private void addInlineItalic(ArrayNode out, String text) {
        Matcher m = ITALIC.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addInlineStrike(out, text.substring(last, m.start()));
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            var node = out.addObject();
            node.put("type", "text");
            node.put("text", inner);
            node.putArray("marks").addObject().put("type", "em");
            last = m.end();
        }
        if (last < text.length()) addInlineStrike(out, text.substring(last));
    }

    private void addInlineStrike(ArrayNode out, String text) {
        Matcher m = STRIKE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addPlainText(out, text.substring(last, m.start()));
            var node = out.addObject();
            node.put("type", "text");
            node.put("text", m.group(1));
            node.putArray("marks").addObject().put("type", "strike");
            last = m.end();
        }
        if (last < text.length()) addPlainText(out, text.substring(last));
    }

    private void addPlainText(ArrayNode out, String text) {
        if (!text.isEmpty()) out.addObject().put("type", "text").put("text", text);
    }
}
