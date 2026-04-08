package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import com.eneve.agent.agent.store.CodeGraphStore;

import java.util.Set;

/**
 * Strategy interface for Tree-sitter-based code graph indexing.
 *
 * <p>Each implementation handles one language by walking the Tree-sitter CST
 * and emitting nodes/edges to {@link CodeGraphStore}.
 *
 * <p><b>Thread safety:</b> implementations must be safe to call concurrently.
 * The recommended pattern is to use a {@code ThreadLocal<Parser>} per driver
 * so that each thread gets its own parser instance (parsers are not thread-safe).
 */
public interface TreeSitterLanguageDriver {

    /** The Tree-sitter {@link Language} this driver handles. */
    Language language();

    /** File extensions handled by this driver (lower-case, without leading dot). */
    Set<String> extensions();

    /**
     * Walk the CST rooted at {@code root} and emit graph nodes/edges to {@code store}.
     *
     * @param root       the root node of the parsed tree
     * @param source     the full source text (used for name extraction)
     * @param wsName     workspace name
     * @param repoSlug   repository slug
     * @param filePath   relative file path (used as the file key in the graph)
     * @param store      the graph store to write to
     */
    void index(Node root, String source, String wsName, String repoSlug,
               String filePath, CodeGraphStore store);
}
