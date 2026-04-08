package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import ch.usi.si.seart.treesitter.Point;
import com.eneve.agent.agent.store.CodeGraphStore;
import org.jboss.logging.Logger;

/**
 * Base class for Tree-sitter language drivers.
 *
 * <p>Manages a {@code ThreadLocal<Parser>} per driver instance so that each
 * thread gets its own parser (parsers are not thread-safe). The {@link Language}
 * object is shared across threads (it is immutable after construction).
 */
abstract class AbstractTreeSitterDriver implements TreeSitterLanguageDriver {

    protected final Logger log = Logger.getLogger(getClass());

    /**
     * Thread-local parser — created lazily per thread, reused across files.
     * Each driver subclass inherits its own ThreadLocal bound to its language.
     */
    private final ThreadLocal<Parser> parserLocal = ThreadLocal.withInitial(() -> {
        try {
            return Parser.getFor(language());
        } catch (Exception e) {
            log.warnf("Failed to create Tree-sitter parser for %s: %s", language(), e.getMessage());
            return null;
        }
    });

    /**
     * Parse {@code source} and return the root node, or {@code null} if parsing fails.
     * The returned node is only valid for the duration of the current call — do not
     * store it beyond the scope of {@link #index}.
     */
    protected Node parse(String source) {
        Parser parser = parserLocal.get();
        if (parser == null) return null;
        try {
            var tree = parser.parse(source);
            return tree != null ? tree.getRootNode() : null;
        } catch (Exception e) {
            log.debugf("Tree-sitter parse error for %s: %s", language(), e.getMessage());
            return null;
        }
    }

    /**
     * Returns the 1-based line number of a node (Tree-sitter rows are 0-based).
     */
    protected static int lineOf(Node node) {
        Point p = node.getStartPoint();
        return p != null ? p.getRow() + 1 : 0;
    }

    /**
     * Returns the text content of a node's named child with the given field name,
     * or {@code null} if the child does not exist.
     */
    protected static String childText(Node node, String fieldName) {
        Node child = node.getChildByFieldName(fieldName);
        return (child != null && !child.isNull()) ? child.getContent() : null;
    }

    /**
     * Recursively walks all descendants of {@code node} looking for nodes whose
     * type matches {@code targetType}, invoking {@code visitor} for each match.
     */
    protected static void walkDescendants(Node node, String targetType, java.util.function.Consumer<Node> visitor) {
        if (node == null || node.isNull()) return;
        for (Node child : node) {
            if (targetType.equals(child.getType())) {
                visitor.accept(child);
            }
        }
    }

    /**
     * Emit a {@code CLASS} or {@code INTERFACE} node and optionally {@code EXTENDS} /
     * {@code IMPLEMENTS} edges to the store.
     */
    protected static void emitTypeNode(CodeGraphStore store, String wsName, String repoSlug,
                                       String filePath, String typeName, String symbolType,
                                       int lineNum, String modifiers) {
        store.upsertNode(wsName, repoSlug, filePath, typeName, symbolType, lineNum, null, modifiers);
    }

    /**
     * Emit a {@code METHOD} node to the store.
     */
    protected static void emitMethodNode(CodeGraphStore store, String wsName, String repoSlug,
                                         String filePath, String qualifiedName, int lineNum, String modifiers) {
        store.upsertNode(wsName, repoSlug, filePath, qualifiedName, "METHOD", lineNum, null, modifiers);
    }

    /**
     * Emit a {@code CALLS} edge to the store.
     */
    protected static void emitCallEdge(CodeGraphStore store, String wsName, String repoSlug,
                                       String filePath, String callerType, String callTarget) {
        store.upsertEdge(wsName, repoSlug, callerType, callTarget, "CALLS", filePath, null);
    }
}
