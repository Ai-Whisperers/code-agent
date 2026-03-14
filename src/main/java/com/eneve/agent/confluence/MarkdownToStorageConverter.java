package com.eneve.agent.confluence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown to Confluence XHTML storage format.
 * Handles headings, code fences (including Mermaid), bold, italic,
 * links, images, tables, lists, and blockquotes.
 */
public final class MarkdownToStorageConverter {

    private MarkdownToStorageConverter() {}

    private static final Pattern FENCED_CODE = Pattern.compile(
            "```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BLOCKQUOTE_LINE = Pattern.compile("^>\\s?(.*)$", Pattern.MULTILINE);
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|(.+)\\|\\s*$", Pattern.MULTILINE);
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[-:|\\s]+\\|\\s*$", Pattern.MULTILINE);

    /**
     * Converts a Markdown document to Confluence storage format (XHTML).
     */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String result = markdown;

        result = convertFencedCode(result);
        result = convertTables(result);
        result = convertBlockquotes(result);
        result = convertLists(result);
        result = convertHeadings(result);
        result = convertImages(result);
        result = convertLinks(result);
        result = convertBold(result);
        result = convertItalic(result);
        result = convertInlineCode(result);
        result = convertParagraphs(result);

        return result.trim();
    }

    private static String convertFencedCode(String input) {
        Matcher m = FENCED_CODE.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2);

            String replacement;
            if ("mermaid".equalsIgnoreCase(lang)) {
                replacement = "<ac:structured-macro ac:name=\"mermaid\">"
                        + "<ac:plain-text-body><![CDATA[" + code.trim() + "]]></ac:plain-text-body>"
                        + "</ac:structured-macro>";
            } else {
                String langParam = (lang != null && !lang.isBlank())
                        ? "<ac:parameter ac:name=\"language\">" + escapeXml(lang) + "</ac:parameter>"
                        : "";
                replacement = "<ac:structured-macro ac:name=\"code\">"
                        + langParam
                        + "<ac:plain-text-body><![CDATA[" + code.trim() + "]]></ac:plain-text-body>"
                        + "</ac:structured-macro>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String convertHeadings(String input) {
        Matcher m = HEADING.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int level = m.group(1).length();
            String text = m.group(2).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<h" + level + ">" + text + "</h" + level + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String convertBold(String input) {
        return BOLD.matcher(input).replaceAll("<strong>$1</strong>");
    }

    private static String convertItalic(String input) {
        return ITALIC.matcher(input).replaceAll("<em>$1</em>");
    }

    private static String convertLinks(String input) {
        return LINK.matcher(input).replaceAll("<a href=\"$2\">$1</a>");
    }

    private static String convertImages(String input) {
        Matcher m = IMAGE.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt = m.group(1);
            String src = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<ac:image><ri:url ri:value=\"" + escapeXml(src) + "\" />"
                    + "<ac:parameter ac:name=\"alt\">" + escapeXml(alt) + "</ac:parameter>"
                    + "</ac:image>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String convertInlineCode(String input) {
        return INLINE_CODE.matcher(input).replaceAll("<code>$1</code>");
    }

    private static String convertBlockquotes(String input) {
        StringBuilder sb = new StringBuilder();
        boolean inBlockquote = false;

        for (String line : input.split("\n")) {
            Matcher m = BLOCKQUOTE_LINE.matcher(line);
            if (m.matches()) {
                if (!inBlockquote) {
                    sb.append("<blockquote>");
                    inBlockquote = true;
                }
                sb.append(m.group(1)).append("<br/>");
            } else {
                if (inBlockquote) {
                    sb.append("</blockquote>\n");
                    inBlockquote = false;
                }
                sb.append(line).append("\n");
            }
        }
        if (inBlockquote) {
            sb.append("</blockquote>\n");
        }
        return sb.toString();
    }

    private static String convertLists(String input) {
        StringBuilder sb = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;

        for (String line : input.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^[-*+]\\s+.+")) {
                if (!inUl) {
                    if (inOl) { sb.append("</ol>\n"); inOl = false; }
                    sb.append("<ul>\n");
                    inUl = true;
                }
                sb.append("<li>").append(trimmed.replaceFirst("^[-*+]\\s+", "")).append("</li>\n");
            } else if (trimmed.matches("^\\d+\\.\\s+.+")) {
                if (!inOl) {
                    if (inUl) { sb.append("</ul>\n"); inUl = false; }
                    sb.append("<ol>\n");
                    inOl = true;
                }
                sb.append("<li>").append(trimmed.replaceFirst("^\\d+\\.\\s+", "")).append("</li>\n");
            } else {
                if (inUl) { sb.append("</ul>\n"); inUl = false; }
                if (inOl) { sb.append("</ol>\n"); inOl = false; }
                sb.append(line).append("\n");
            }
        }
        if (inUl) sb.append("</ul>\n");
        if (inOl) sb.append("</ol>\n");
        return sb.toString();
    }

    private static String convertTables(String input) {
        String[] lines = input.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inTable = false;
        boolean headerDone = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }

            Matcher rowMatcher = TABLE_ROW.matcher(line);
            if (rowMatcher.matches()) {
                if (!inTable) {
                    sb.append("<table><tbody>\n");
                    inTable = true;
                    headerDone = false;
                }

                String[] cells = rowMatcher.group(1).split("\\|");
                String cellTag = !headerDone ? "th" : "td";
                sb.append("<tr>");
                for (String cell : cells) {
                    sb.append("<").append(cellTag).append(">")
                      .append(cell.trim())
                      .append("</").append(cellTag).append(">");
                }
                sb.append("</tr>\n");

                if (!headerDone) {
                    headerDone = true;
                }
            } else {
                if (inTable) {
                    sb.append("</tbody></table>\n");
                    inTable = false;
                }
                sb.append(lines[i]).append("\n");
            }
        }
        if (inTable) {
            sb.append("</tbody></table>\n");
        }
        return sb.toString();
    }

    private static String convertParagraphs(String input) {
        String[] lines = input.split("\n\n");
        StringBuilder sb = new StringBuilder();
        for (String block : lines) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("<h") || trimmed.startsWith("<ul")
                    || trimmed.startsWith("<ol") || trimmed.startsWith("<table")
                    || trimmed.startsWith("<blockquote") || trimmed.startsWith("<ac:")) {
                sb.append(trimmed).append("\n");
            } else {
                sb.append("<p>").append(trimmed.replace("\n", "<br/>")).append("</p>\n");
            }
        }
        return sb.toString();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
