package com.eneve.agent.confluence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown to Confluence XHTML storage format.
 * Handles headings, code fences (including Mermaid), bold, italic,
 * links, images, tables, lists, and blockquotes.
 * <p>
 * Mermaid diagrams are extracted as {@link MermaidDiagram} entries so the
 * caller can render them server-side and upload as page attachments.
 */
public final class MarkdownToStorageConverter {

    private MarkdownToStorageConverter() {}

    private static final String PLACEHOLDER_PREFIX = "\u00abCODE_BLOCK_";
    private static final String PLACEHOLDER_SUFFIX = "\u00bb";

    private static final Pattern FENCED_CODE = Pattern.compile(
            "^```(\\w*)\\s*\\n(.*?)^```\\s*$", Pattern.DOTALL | Pattern.MULTILINE);
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
    private static final Pattern LIST_ITEM_UL = Pattern.compile("^[-*+]\\s+(.+)");
    private static final Pattern LIST_ITEM_OL = Pattern.compile("^\\d+\\.\\s+(.+)");

    public record MermaidDiagram(String filename, String sourceCode) {}

    public record ConversionResult(String xhtml, List<MermaidDiagram> mermaidDiagrams) {}

    /**
     * Converts a Markdown document to Confluence storage format (XHTML).
     * Mermaid code blocks are converted to attachment image references and
     * returned separately so the caller can render and upload them.
     */
    public static ConversionResult convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new ConversionResult("", Collections.emptyList());
        }

        String normalized = normalizeMarkdown(markdown);

        List<String> codeBlocks = new ArrayList<>();
        List<MermaidDiagram> mermaidDiagrams = new ArrayList<>();
        String result = extractCodeBlocks(normalized, codeBlocks, mermaidDiagrams);

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
        result = cleanupHtml(result);

        result = restoreCodeBlocks(result, codeBlocks);

        return new ConversionResult(result.trim(), mermaidDiagrams);
    }

    private static String extractCodeBlocks(String input, List<String> codeBlocks,
                                            List<MermaidDiagram> mermaidDiagrams) {
        Matcher m = FENCED_CODE.matcher(input);
        StringBuilder sb = new StringBuilder();
        int mermaidIndex = 0;
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2);

            String html;
            if ("mermaid".equalsIgnoreCase(lang)) {
                mermaidIndex++;
                String filename = "mermaid-" + mermaidIndex + ".png";
                mermaidDiagrams.add(new MermaidDiagram(filename, code.trim()));
                html = "<ac:image ac:width=\"800\">"
                        + "<ri:attachment ri:filename=\"" + filename + "\" />"
                        + "</ac:image>";
            } else {
                String langParam = (lang != null && !lang.isBlank())
                        ? "<ac:parameter ac:name=\"language\">" + escapeXml(lang) + "</ac:parameter>"
                        : "";
                html = "<ac:structured-macro ac:name=\"code\">"
                        + langParam
                        + "<ac:plain-text-body><![CDATA[" + code.trim() + "]]></ac:plain-text-body>"
                        + "</ac:structured-macro>";
            }

            int index = codeBlocks.size();
            codeBlocks.add(html);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    PLACEHOLDER_PREFIX + index + PLACEHOLDER_SUFFIX));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String restoreCodeBlocks(String input, List<String> codeBlocks) {
        String result = input;
        for (int i = 0; i < codeBlocks.size(); i++) {
            result = result.replace(PLACEHOLDER_PREFIX + i + PLACEHOLDER_SUFFIX, codeBlocks.get(i));
        }
        return result;
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
        String[] lines = input.split("\n");
        boolean inUl = false;
        boolean inOl = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.isEmpty() && (inUl || inOl)) {
                if (hasUpcomingListItem(lines, i + 1, inUl)) {
                    continue;
                }
            }

            Matcher ulMatcher = LIST_ITEM_UL.matcher(trimmed);
            Matcher olMatcher = LIST_ITEM_OL.matcher(trimmed);

            if (ulMatcher.matches()) {
                if (!inUl) {
                    if (inOl) { sb.append("</ol>\n"); inOl = false; }
                    sb.append("<ul>\n");
                    inUl = true;
                }
                sb.append("<li>").append(ulMatcher.group(1)).append("</li>\n");
            } else if (olMatcher.matches()) {
                if (!inOl) {
                    if (inUl) { sb.append("</ul>\n"); inUl = false; }
                    sb.append("<ol>\n");
                    inOl = true;
                }
                sb.append("<li>").append(olMatcher.group(1)).append("</li>\n");
            } else {
                if (inUl) { sb.append("</ul>\n"); inUl = false; }
                if (inOl) { sb.append("</ol>\n"); inOl = false; }
                sb.append(lines[i]).append("\n");
            }
        }
        if (inUl) sb.append("</ul>\n");
        if (inOl) sb.append("</ol>\n");
        return sb.toString();
    }

    private static boolean hasUpcomingListItem(String[] lines, int from, boolean expectUl) {
        for (int i = from; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (expectUl) return LIST_ITEM_UL.matcher(t).matches();
            return LIST_ITEM_OL.matcher(t).matches();
        }
        return false;
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
        String[] blocks = input.split("\n\n");
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("<h") || trimmed.startsWith("<ul")
                    || trimmed.startsWith("<ol") || trimmed.startsWith("<table")
                    || trimmed.startsWith("<blockquote") || trimmed.startsWith("<ac:")
                    || trimmed.startsWith(PLACEHOLDER_PREFIX)) {
                sb.append(trimmed).append("\n");
            } else {
                sb.append("<p>").append(trimmed.replace("\n", "<br/>")).append("</p>\n");
            }
        }
        return sb.toString();
    }

    /**
     * Normalizes markdown before conversion:
     * - Collapses whitespace-only lines to truly empty lines
     * - Ensures a blank line exists before the first list item so
     *   convertParagraphs doesn't merge text and lists into one block
     */
    private static String normalizeMarkdown(String markdown) {
        String[] lines = markdown.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                sb.append("\n");
                continue;
            }
            boolean isList = LIST_ITEM_UL.matcher(line.trim()).matches()
                    || LIST_ITEM_OL.matcher(line.trim()).matches();
            if (isList && i > 0) {
                String prev = lines[i - 1];
                boolean prevEmpty = prev.trim().isEmpty();
                boolean prevIsList = LIST_ITEM_UL.matcher(prev.trim()).matches()
                        || LIST_ITEM_OL.matcher(prev.trim()).matches();
                if (!prevEmpty && !prevIsList) {
                    sb.append("\n");
                }
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Strips artefacts from the final HTML: empty list items, empty paragraphs,
     * and stray whitespace-only tags.
     */
    private static String cleanupHtml(String html) {
        String result = html;
        result = result.replaceAll("<li>\\s*</li>\\n?", "");
        result = result.replaceAll("<p>\\s*</p>\\n?", "");
        result = result.replaceAll("<p>\\s*<br/>\\s*</p>\\n?", "");
        result = result.replaceAll("(<ul>\\n?)\\s*</ul>\\n?", "");
        result = result.replaceAll("(<ol>\\n?)\\s*</ol>\\n?", "");
        return result;
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
