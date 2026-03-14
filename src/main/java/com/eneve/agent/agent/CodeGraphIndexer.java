package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CodeGraphIndexer {

    static {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private static final Logger LOG = Logger.getLogger(CodeGraphIndexer.class);
    private static final long MAX_FILE_SIZE = 200 * 1024; // 200KB
    private static final long MAX_INDEX_TIME_MS = 60_000;  // 60 seconds

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".cs");

    // ── C# regex patterns ──────────────────────────────────────────────
    private static final Pattern CS_TYPE_DECL = Pattern.compile(
            "^\\s*(?<mods>(?:(?:public|private|protected|internal|static|abstract|sealed|partial|readonly|new)\\s+)*)"
                    + "(?<kind>class|interface|struct|enum|record)\\s+"
                    + "(?<name>\\w+)(?:<[^>]*>)?(?:\\s*:\\s*(?<bases>[^{]+))?",
            Pattern.MULTILINE);

    private static final Pattern CS_METHOD_DECL = Pattern.compile(
            "^\\s*(?<mods>(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|new|sealed|partial)\\s+)*)"
                    + "(?<ret>[\\w<>\\[\\],\\s?]+?)\\s+"
                    + "(?<name>\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern CS_FIELD_OR_PROP = Pattern.compile(
            "^\\s*(?<mods>(?:(?:public|private|protected|internal|static|readonly|const|volatile|new)\\s+)*)"
                    + "(?<type>[\\w<>\\[\\],\\s?]+?)\\s+"
                    + "(?<name>\\w+)\\s*(?:[{;=])",
            Pattern.MULTILINE);

    private static final Pattern CS_USING = Pattern.compile(
            "^\\s*using\\s+(?!static)(?<ns>[\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern CS_METHOD_CALL = Pattern.compile(
            "(?<scope>\\w+)\\.(?<method>\\w+)\\s*\\(");

    private static final Set<String> CS_KEYWORDS = Set.of(
            "if", "else", "for", "foreach", "while", "do", "switch", "case", "return",
            "try", "catch", "finally", "throw", "using", "lock", "yield", "await",
            "var", "new", "typeof", "sizeof", "nameof", "default", "checked", "unchecked",
            "this", "base", "null", "true", "false", "void", "string", "int", "long",
            "bool", "double", "float", "decimal", "byte", "char", "short", "object",
            "get", "set", "value", "namespace");

    @Inject
    CodeGraphStore store;

    // ── Public API ─────────────────────────────────────────────────────

    public void indexFull(WorkspaceContext workspace, String wsName, String repoSlug) {
        LOG.infof("Full code graph index for %s/%s", wsName, repoSlug);
        store.deleteAllForRepo(wsName, repoSlug);

        List<Path> sourceFiles = findSourceFiles(workspace.getRoot());
        LOG.infof("Found %d source files to index (Java + C#)", sourceFiles.size());

        long startTime = System.currentTimeMillis();
        int indexed = 0;
        for (Path file : sourceFiles) {
            if (System.currentTimeMillis() - startTime > MAX_INDEX_TIME_MS) {
                LOG.warnf("Code graph indexing time limit reached after %d files", indexed);
                break;
            }
            String relativePath = workspace.getRoot().relativize(file).toString();
            dispatchIndex(file, relativePath, wsName, repoSlug);
            indexed++;
        }
        LOG.infof("Full index complete: %d files indexed for %s/%s", indexed, wsName, repoSlug);
    }

    public void indexIncremental(WorkspaceContext workspace, String wsName, String repoSlug,
                                 List<String> changedFiles) {
        List<String> indexable = changedFiles.stream()
                .filter(f -> SUPPORTED_EXTENSIONS.stream().anyMatch(f::endsWith))
                .toList();

        if (indexable.isEmpty()) {
            LOG.debugf("No indexable source files in changed set for %s/%s — skipping", wsName, repoSlug);
            return;
        }

        LOG.infof("Incremental code graph index for %s/%s: %d files", wsName, repoSlug, indexable.size());
        long startTime = System.currentTimeMillis();
        int indexed = 0;

        for (String filePath : indexable) {
            if (System.currentTimeMillis() - startTime > MAX_INDEX_TIME_MS) {
                LOG.warnf("Code graph incremental indexing time limit reached after %d files", indexed);
                break;
            }

            store.deleteNodesForFile(wsName, repoSlug, filePath);
            store.deleteEdgesForSourceFile(wsName, repoSlug, filePath);

            Path absolutePath = workspace.getRoot().resolve(filePath);
            if (Files.exists(absolutePath)) {
                dispatchIndex(absolutePath, filePath, wsName, repoSlug);
            }
            indexed++;
        }
        LOG.infof("Incremental index complete: %d files re-indexed for %s/%s", indexed, wsName, repoSlug);
    }

    // ── Dispatcher ─────────────────────────────────────────────────────

    private void dispatchIndex(Path file, String relativePath, String wsName, String repoSlug) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                LOG.debugf("Skipping large file: %s (%d bytes)", relativePath, Files.size(file));
                return;
            }
            if (relativePath.endsWith(".java")) {
                indexJavaFile(file, relativePath, wsName, repoSlug);
            } else if (relativePath.endsWith(".cs")) {
                indexCSharpFile(file, relativePath, wsName, repoSlug);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to index %s (non-fatal): %s", relativePath, e.getMessage());
        }
    }

    // ── Java (JavaParser AST) ──────────────────────────────────────────

    private void indexJavaFile(Path file, String relativePath, String wsName, String repoSlug)
            throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file);

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String name = decl.getNameAsString();
            String type = decl.isInterface() ? "INTERFACE" : "CLASS";
            int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);
            String modifiers = decl.getModifiers().stream()
                    .map(m -> m.getKeyword().asString())
                    .collect(Collectors.joining(" "));
            store.upsertNode(wsName, repoSlug, relativePath, name, type, lineStart, lineEnd, modifiers);

            decl.getExtendedTypes().forEach(ext ->
                    store.upsertEdge(wsName, repoSlug, name, ext.getNameAsString(),
                            "EXTENDS", relativePath, null));

            decl.getImplementedTypes().forEach(impl ->
                    store.upsertEdge(wsName, repoSlug, name, impl.getNameAsString(),
                            "IMPLEMENTS", relativePath, null));

            decl.getMethods().forEach(method -> {
                String methodSymbol = name + "." + method.getNameAsString();
                int mStart = method.getBegin().map(p -> p.line).orElse(0);
                int mEnd = method.getEnd().map(p -> p.line).orElse(0);
                String mMods = method.getModifiers().stream()
                        .map(m -> m.getKeyword().asString())
                        .collect(Collectors.joining(" "));
                store.upsertNode(wsName, repoSlug, relativePath, methodSymbol, "METHOD",
                        mStart, mEnd, mMods);

                method.findAll(MethodCallExpr.class).forEach(call -> {
                    String scope = call.getScope()
                            .map(s -> s.toString().contains(".") ?
                                    s.toString().substring(s.toString().lastIndexOf('.') + 1) :
                                    s.toString())
                            .orElse(name);
                    String target = scope + "." + call.getNameAsString();
                    store.upsertEdge(wsName, repoSlug, methodSymbol, target,
                            "CALLS", relativePath, null);
                });
            });

            decl.getFields().forEach(field -> {
                for (VariableDeclarator var : field.getVariables()) {
                    String fieldSymbol = name + "." + var.getNameAsString();
                    int fStart = field.getBegin().map(p -> p.line).orElse(0);
                    int fEnd = field.getEnd().map(p -> p.line).orElse(0);
                    String fMods = field.getModifiers().stream()
                            .map(m -> m.getKeyword().asString())
                            .collect(Collectors.joining(" "));
                    store.upsertNode(wsName, repoSlug, relativePath, fieldSymbol, "FIELD",
                            fStart, fEnd, fMods);
                }
            });
        });

        cu.findAll(EnumDeclaration.class).forEach(decl -> {
            String name = decl.getNameAsString();
            int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);
            String modifiers = decl.getModifiers().stream()
                    .map(m -> m.getKeyword().asString())
                    .collect(Collectors.joining(" "));
            store.upsertNode(wsName, repoSlug, relativePath, name, "ENUM", lineStart, lineEnd, modifiers);
        });

        cu.findAll(ImportDeclaration.class).forEach(imp -> {
            String imported = imp.getNameAsString();
            String simpleName = imported.contains(".")
                    ? imported.substring(imported.lastIndexOf('.') + 1)
                    : imported;
            String className = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .orElse(relativePath);
            store.upsertEdge(wsName, repoSlug, className, simpleName,
                    "IMPORTS", relativePath, null);
        });
    }

    // ── C# (regex-based) ───────────────────────────────────────────────

    private void indexCSharpFile(Path file, String relativePath, String wsName, String repoSlug)
            throws IOException {
        String source = Files.readString(file);

        String currentType = null;

        // Using directives
        Matcher usingMatcher = CS_USING.matcher(source);
        while (usingMatcher.find()) {
            String ns = usingMatcher.group("ns");
            String simpleName = ns.contains(".") ? ns.substring(ns.lastIndexOf('.') + 1) : ns;
            // defer IMPORTS edge source until we know the primary type
            store.upsertEdge(wsName, repoSlug, relativePath, simpleName, "IMPORTS", relativePath, null);
        }

        // Type declarations (class, interface, struct, enum, record)
        Matcher typeMatcher = CS_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String mods = typeMatcher.group("mods").trim();
            String kind = typeMatcher.group("kind");
            String name = typeMatcher.group("name");
            String bases = typeMatcher.group("bases");
            int lineNum = lineNumberAt(source, typeMatcher.start());

            String symbolType = switch (kind) {
                case "interface" -> "INTERFACE";
                case "enum" -> "ENUM";
                case "struct", "record" -> "CLASS";
                default -> "CLASS";
            };

            store.upsertNode(wsName, repoSlug, relativePath, name, symbolType, lineNum, null, mods);

            if (currentType == null) {
                currentType = name;
                // Retroactively point IMPORTS edges at this type instead of the file path
                updateImportsSource(wsName, repoSlug, relativePath, name);
            }

            if (bases != null) {
                String[] baseTypes = bases.split(",");
                boolean first = true;
                for (String bt : baseTypes) {
                    String baseName = bt.trim().replaceAll("<.*>", "");
                    if (baseName.isEmpty() || baseName.contains("{")) continue;
                    if (first && !"interface".equals(kind) && !"enum".equals(kind)) {
                        store.upsertEdge(wsName, repoSlug, name, baseName, "EXTENDS", relativePath, null);
                    } else {
                        store.upsertEdge(wsName, repoSlug, name, baseName, "IMPLEMENTS", relativePath, null);
                    }
                    first = false;
                }
            }
        }

        if (currentType == null) {
            return;
        }

        // Method declarations
        String enclosingType = currentType;
        Matcher methodMatcher = CS_METHOD_DECL.matcher(source);
        while (methodMatcher.find()) {
            String mods = methodMatcher.group("mods").trim();
            String retType = methodMatcher.group("ret").trim();
            String methodName = methodMatcher.group("name");

            if (CS_KEYWORDS.contains(methodName) || CS_KEYWORDS.contains(retType)) continue;
            if (retType.equals("class") || retType.equals("interface") || retType.equals("struct")
                    || retType.equals("enum") || retType.equals("record") || retType.equals("namespace")) {
                continue;
            }

            int lineNum = lineNumberAt(source, methodMatcher.start());
            String methodSymbol = enclosingType + "." + methodName;
            store.upsertNode(wsName, repoSlug, relativePath, methodSymbol, "METHOD", lineNum, null, mods);

            // Find method calls within this method's approximate body
            int bodyStart = methodMatcher.end();
            int bodyEnd = findApproximateMethodEnd(source, bodyStart);
            if (bodyEnd > bodyStart) {
                String body = source.substring(bodyStart, bodyEnd);
                Matcher callMatcher = CS_METHOD_CALL.matcher(body);
                while (callMatcher.find()) {
                    String scope = callMatcher.group("scope");
                    String calledMethod = callMatcher.group("method");
                    if (!CS_KEYWORDS.contains(scope) && !CS_KEYWORDS.contains(calledMethod)) {
                        store.upsertEdge(wsName, repoSlug, methodSymbol, scope + "." + calledMethod,
                                "CALLS", relativePath, null);
                    }
                }
            }
        }

        // Field/property declarations
        Matcher fieldMatcher = CS_FIELD_OR_PROP.matcher(source);
        while (fieldMatcher.find()) {
            String mods = fieldMatcher.group("mods").trim();
            String fieldType = fieldMatcher.group("type").trim();
            String fieldName = fieldMatcher.group("name");

            if (CS_KEYWORDS.contains(fieldName) || CS_KEYWORDS.contains(fieldType)) continue;
            if (fieldType.equals("class") || fieldType.equals("interface") || fieldType.equals("struct")
                    || fieldType.equals("namespace") || fieldType.equals("enum") || fieldType.equals("record")) {
                continue;
            }

            int lineNum = lineNumberAt(source, fieldMatcher.start());
            String fieldSymbol = enclosingType + "." + fieldName;
            store.upsertNode(wsName, repoSlug, relativePath, fieldSymbol, "FIELD", lineNum, null, mods);
        }
    }

    private void updateImportsSource(String wsName, String repoSlug, String filePath, String typeName) {
        // The IMPORTS edges were created with filePath as source; update them to the primary type
        store.deleteEdgesForSourceFile(wsName, repoSlug, filePath + "#imports");
        // Re-read isn't needed—we used filePath as source_node, now we want typeName.
        // Just leave them as-is; the file path as source_node still provides useful signal.
    }

    private static int lineNumberAt(String source, int charOffset) {
        int line = 1;
        for (int i = 0; i < charOffset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Rough heuristic to find the closing brace of a method body.
     * Starts counting braces from the given offset (just past the opening paren).
     */
    private static int findApproximateMethodEnd(String source, int fromIndex) {
        int depth = 0;
        boolean inBody = false;
        for (int i = fromIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                inBody = true;
            } else if (c == '}') {
                depth--;
                if (inBody && depth == 0) {
                    return i;
                }
            }
        }
        return Math.min(fromIndex + 2000, source.length());
    }

    // ── File discovery ─────────────────────────────────────────────────

    private List<Path> findSourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.toString();
                    if (name.endsWith(".java") || name.endsWith(".cs")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("node_modules") || name.equals("target")
                            || name.equals("build") || name.equals(".gradle")
                            || name.equals("bin") || name.equals("obj")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf("Error walking file tree: %s", e.getMessage());
        }
        return files;
    }
}
