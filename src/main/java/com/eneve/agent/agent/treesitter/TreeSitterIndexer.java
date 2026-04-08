package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.LibraryLoader;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import com.eneve.agent.agent.store.CodeGraphStore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Application-scoped service that owns the Tree-sitter driver registry and
 * dispatches indexing requests to the appropriate {@link TreeSitterLanguageDriver}.
 *
 * <p>The native library is loaded once at startup via {@link LibraryLoader#load()}.
 * If loading fails (e.g. unsupported platform), all {@link #index} calls are no-ops
 * and the caller falls back to the regex-based path.
 *
 * <p><b>Thread safety:</b> the driver registry is immutable after construction.
 * Each driver manages its own {@code ThreadLocal<Parser>} for thread-safe parsing.
 */
@ApplicationScoped
public class TreeSitterIndexer {

    private static final Logger LOG = Logger.getLogger(TreeSitterIndexer.class);

    /** Extensions handled by Tree-sitter drivers (excludes .java which uses JavaParser). */
    public static final Set<String> HANDLED_EXTENSIONS = Set.of(".cs", ".ts", ".tsx", ".php", ".blade.php");

    @Inject
    CodeGraphStore store;

    private Map<String, TreeSitterLanguageDriver> driverByExtension;
    private boolean available = false;

    @PostConstruct
    void init() {
        try {
            LibraryLoader.load();
            driverByExtension = buildDriverMap();
            available = true;
            LOG.infof("Tree-sitter indexer initialised — drivers: %s", driverByExtension.keySet());
        } catch (Throwable t) {
            LOG.warnf("Tree-sitter native library unavailable — C#/TS/PHP indexing will fall back to regex: %s",
                    t.getMessage());
            driverByExtension = Map.of();
        }
    }

    /**
     * Returns {@code true} if the native library loaded successfully and at least
     * one driver is registered.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Index a single source file using the appropriate Tree-sitter driver.
     *
     * @param file         absolute path to the file
     * @param relativePath relative path used as the graph key
     * @param wsName       workspace name
     * @param repoSlug     repository slug
     * @return {@code true} if the file was handled by a driver, {@code false} if
     *         no driver is registered for this extension (caller should fall back)
     */
    public boolean index(Path file, String relativePath, String wsName, String repoSlug) {
        if (!available) return false;

        String ext = extension(relativePath);
        TreeSitterLanguageDriver driver = driverByExtension.get(ext);
        if (driver == null) return false;

        try {
            String source = Files.readString(file);
            try (Parser parser = Parser.getFor(driver.language())) {
                var tree = parser.parse(source);
                if (tree == null) {
                    LOG.debugf("Tree-sitter returned null tree for %s", relativePath);
                    return true; // handled (no-op)
                }
                Node root = tree.getRootNode();
                driver.index(root, source, wsName, repoSlug, relativePath, store);
            }
        } catch (IOException e) {
            LOG.warnf("Failed to read %s for Tree-sitter indexing: %s", relativePath, e.getMessage());
        } catch (Exception e) {
            LOG.warnf("Tree-sitter indexing failed for %s (non-fatal): %s", relativePath, e.getMessage());
        }
        return true;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────────

    private static Map<String, TreeSitterLanguageDriver> buildDriverMap() {
        Map<String, TreeSitterLanguageDriver> map = new HashMap<>();
        register(map, new CSharpDriver());
        register(map, new TypeScriptDriver());
        register(map, new PhpDriver());
        return Map.copyOf(map);
    }

    private static void register(Map<String, TreeSitterLanguageDriver> map, TreeSitterLanguageDriver driver) {
        for (String ext : driver.extensions()) {
            map.put(ext, driver);
        }
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }
}
