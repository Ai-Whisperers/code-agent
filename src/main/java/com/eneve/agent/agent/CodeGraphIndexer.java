package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import com.eneve.agent.agent.store.CodeGraphStore;

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

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".cs", ".ts", ".tsx", ".php");

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

    // ── TypeScript regex patterns ──────────────────────────────────────
    private static final Pattern TS_TYPE_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?(?<kind>class|interface|enum|type)\\s+(?<name>\\w+)"
                    + "(?:<[^>]*>)?(?:\\s+extends\\s+(?<ext>[\\w<>, ]+?))?(?:\\s+implements\\s+(?<impl>[^{]+?))?\\s*(?:\\{|=)",
            Pattern.MULTILINE);

    private static final Pattern TS_METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|async|readonly|abstract|override)\\s+)*"
                    + "(?<name>\\w+)\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::\\s*[\\w<>\\[\\]|&?,\\s]+?)?\\s*(?:\\{|=>)",
            Pattern.MULTILINE);

    private static final Pattern TS_FUNCTION_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(?<name>\\w+)\\s*(?:<[^>]*>)?\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern TS_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:[\\w*{},\\s]+\\s+from\\s+)?['\"](?<module>[^'\"]+)['\"]",
            Pattern.MULTILINE);

    private static final Pattern TS_METHOD_CALL = Pattern.compile(
            "(?<scope>\\w+)\\.(?<method>\\w+)\\s*\\(");

    private static final Set<String> TS_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
            "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof", "in", "of",
            "this", "super", "null", "undefined", "true", "false", "void", "let", "const", "var",
            "async", "await", "yield", "from", "import", "export", "default", "class", "extends",
            "implements", "interface", "type", "enum", "namespace", "module", "declare", "abstract",
            "get", "set", "static", "public", "private", "protected", "readonly", "override",
            "console", "Math", "Object", "Array", "String", "Number", "Boolean", "Promise",
            "require", "constructor");

    // ── PHP regex patterns ─────────────────────────────────────────────
    private static final Pattern PHP_TYPE_DECL = Pattern.compile(
            "^\\s*(?:(?:abstract|final|readonly)\\s+)*(?<kind>class|interface|trait|enum)\\s+(?<name>\\w+)"
                    + "(?:\\s+extends\\s+(?<ext>[\\w\\\\]+))?(?:\\s+implements\\s+(?<impl>[^{]+?))?\\s*\\{",
            Pattern.MULTILINE);

    private static final Pattern PHP_METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|abstract|final)\\s+)*function\\s+(?<name>\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern PHP_USE = Pattern.compile(
            "^\\s*use\\s+(?<ns>[\\w\\\\]+)(?:\\s+as\\s+\\w+)?\\s*;",
            Pattern.MULTILINE);

    private static final Pattern PHP_METHOD_CALL = Pattern.compile(
            "(?<scope>\\$?\\w+)(?:->|::)(?<method>\\w+)\\s*\\(");

    private static final Set<String> PHP_KEYWORDS = Set.of(
            "if", "else", "elseif", "for", "foreach", "while", "do", "switch", "case", "return",
            "break", "continue", "try", "catch", "finally", "throw", "new", "clone", "echo", "print",
            "isset", "empty", "unset", "list", "array", "null", "true", "false", "self", "parent",
            "static", "abstract", "final", "class", "interface", "trait", "enum", "extends",
            "implements", "namespace", "use", "require", "require_once", "include", "include_once",
            "match", "fn", "yield", "this", "string", "int", "float", "bool", "void", "mixed",
            "never", "object", "callable", "iterable");

    @Inject
    CodeGraphStore store;

    // ── Public API ─────────────────────────────────────────────────────

    public void indexFull(WorkspaceContext workspace, String wsName, String repoSlug) {
        LOG.infof("Full code graph index for %s/%s", wsName, repoSlug);
        store.deleteAllForRepo(wsName, repoSlug);

        List<Path> sourceFiles = findSourceFiles(workspace.getRoot());
        LOG.infof("Found %d source files to index (Java, C#, TypeScript, PHP)", sourceFiles.size());

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
            } else if (relativePath.endsWith(".ts") || relativePath.endsWith(".tsx")) {
                indexTypeScriptFile(file, relativePath, wsName, repoSlug);
            } else if (relativePath.endsWith(".php")) {
                indexPhpFile(file, relativePath, wsName, repoSlug);
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

    // ── TypeScript (regex-based) ───────────────────────────────────────

    private void indexTypeScriptFile(Path file, String relativePath, String wsName, String repoSlug)
            throws IOException {
        String source = Files.readString(file);
        String currentType = null;

        // Import statements
        Matcher importMatcher = TS_IMPORT.matcher(source);
        while (importMatcher.find()) {
            String module = importMatcher.group("module");
            String simpleName = module.contains("/") ? module.substring(module.lastIndexOf('/') + 1) : module;
            store.upsertEdge(wsName, repoSlug, relativePath, simpleName, "IMPORTS", relativePath, null);
        }

        // Type declarations (class, interface, enum, type alias)
        Matcher typeMatcher = TS_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String kind = typeMatcher.group("kind");
            String name = typeMatcher.group("name");
            String ext = typeMatcher.group("ext");
            String impl = typeMatcher.group("impl");
            int lineNum = lineNumberAt(source, typeMatcher.start());

            String symbolType = switch (kind) {
                case "interface" -> "INTERFACE";
                case "enum" -> "ENUM";
                default -> "CLASS";
            };

            store.upsertNode(wsName, repoSlug, relativePath, name, symbolType, lineNum, null, "");

            if (currentType == null) {
                currentType = name;
            }

            if (ext != null) {
                for (String base : ext.split(",")) {
                    String baseName = base.trim().replaceAll("<.*>", "").trim();
                    if (!baseName.isEmpty()) {
                        store.upsertEdge(wsName, repoSlug, name, baseName, "EXTENDS", relativePath, null);
                    }
                }
            }
            if (impl != null) {
                for (String iface : impl.split(",")) {
                    String ifaceName = iface.trim().replaceAll("<.*>", "").trim();
                    if (!ifaceName.isEmpty()) {
                        store.upsertEdge(wsName, repoSlug, name, ifaceName, "IMPLEMENTS", relativePath, null);
                    }
                }
            }
        }

        if (currentType == null) {
            // Module-level functions (no enclosing class)
            currentType = relativePath;
        }

        String enclosingType = currentType;

        // Method declarations (class methods and top-level functions)
        Matcher methodMatcher = TS_METHOD_DECL.matcher(source);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group("name");
            if (TS_KEYWORDS.contains(methodName)) continue;

            int lineNum = lineNumberAt(source, methodMatcher.start());
            String methodSymbol = enclosingType + "." + methodName;
            store.upsertNode(wsName, repoSlug, relativePath, methodSymbol, "METHOD", lineNum, null, "");

            // TS_METHOD_DECL ends with '{', so the brace is already consumed — start from it
            int bodyStart = (methodMatcher.end() > 0 && source.charAt(methodMatcher.end() - 1) == '{')
                    ? methodMatcher.end() - 1 : methodMatcher.end();
            int bodyEnd = findApproximateMethodEnd(source, bodyStart);
            if (bodyEnd > bodyStart) {
                String body = source.substring(bodyStart, bodyEnd);
                Matcher callMatcher = TS_METHOD_CALL.matcher(body);
                while (callMatcher.find()) {
                    String scope = callMatcher.group("scope");
                    String calledMethod = callMatcher.group("method");
                    if (!TS_KEYWORDS.contains(scope) && !TS_KEYWORDS.contains(calledMethod)) {
                        store.upsertEdge(wsName, repoSlug, methodSymbol,
                                scope + "." + calledMethod, "CALLS", relativePath, null);
                    }
                }
            }
        }

        // Top-level exported functions
        Matcher funcMatcher = TS_FUNCTION_DECL.matcher(source);
        while (funcMatcher.find()) {
            String funcName = funcMatcher.group("name");
            if (TS_KEYWORDS.contains(funcName)) continue;
            int lineNum = lineNumberAt(source, funcMatcher.start());
            store.upsertNode(wsName, repoSlug, relativePath, funcName, "METHOD", lineNum, null, "export");
        }
    }

    // ── PHP (regex-based) ─────────────────────────────────────────────

    private void indexPhpFile(Path file, String relativePath, String wsName, String repoSlug)
            throws IOException {
        String source = Files.readString(file);
        String currentType = null;

        // use statements (imports / namespace use)
        Matcher useMatcher = PHP_USE.matcher(source);
        while (useMatcher.find()) {
            String ns = useMatcher.group("ns");
            String simpleName = ns.contains("\\") ? ns.substring(ns.lastIndexOf('\\') + 1) : ns;
            store.upsertEdge(wsName, repoSlug, relativePath, simpleName, "IMPORTS", relativePath, null);
        }

        // Type declarations (class, interface, trait, enum)
        Matcher typeMatcher = PHP_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String kind = typeMatcher.group("kind");
            String name = typeMatcher.group("name");
            String ext = typeMatcher.group("ext");
            String impl = typeMatcher.group("impl");
            int lineNum = lineNumberAt(source, typeMatcher.start());

            String symbolType = switch (kind) {
                case "interface" -> "INTERFACE";
                case "enum" -> "ENUM";
                case "trait" -> "CLASS";
                default -> "CLASS";
            };

            store.upsertNode(wsName, repoSlug, relativePath, name, symbolType, lineNum, null, "");

            if (currentType == null) {
                currentType = name;
            }

            if (ext != null) {
                String baseName = ext.trim().replaceAll("\\\\", ".").trim();
                if (!baseName.isEmpty()) {
                    store.upsertEdge(wsName, repoSlug, name, baseName, "EXTENDS", relativePath, null);
                }
            }
            if (impl != null) {
                for (String iface : impl.split(",")) {
                    String ifaceName = iface.trim().replaceAll("\\\\", ".").trim();
                    if (!ifaceName.isEmpty()) {
                        store.upsertEdge(wsName, repoSlug, name, ifaceName, "IMPLEMENTS", relativePath, null);
                    }
                }
            }
        }

        if (currentType == null) {
            currentType = relativePath;
        }

        String enclosingType = currentType;

        // Method declarations
        Matcher methodMatcher = PHP_METHOD_DECL.matcher(source);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group("name");
            if (PHP_KEYWORDS.contains(methodName)) continue;

            int lineNum = lineNumberAt(source, methodMatcher.start());
            String methodSymbol = enclosingType + "." + methodName;
            store.upsertNode(wsName, repoSlug, relativePath, methodSymbol, "METHOD", lineNum, null, "");

            int bodyStart = methodMatcher.end();
            int bodyEnd = findApproximateMethodEnd(source, bodyStart);
            if (bodyEnd > bodyStart) {
                String body = source.substring(bodyStart, bodyEnd);
                Matcher callMatcher = PHP_METHOD_CALL.matcher(body);
                while (callMatcher.find()) {
                    String scope = callMatcher.group("scope");
                    String calledMethod = callMatcher.group("method");
                    // Strip $ prefix from variable names for cleaner symbol names
                    String cleanScope = scope.startsWith("$") ? scope.substring(1) : scope;
                    if (!PHP_KEYWORDS.contains(cleanScope) && !PHP_KEYWORDS.contains(calledMethod)) {
                        store.upsertEdge(wsName, repoSlug, methodSymbol,
                                cleanScope + "." + calledMethod, "CALLS", relativePath, null);
                    }
                }
            }
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

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".gradle",
            "bin", "obj", "vendor", "dist", "out", ".next", ".nuxt");

    private List<Path> findSourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.toString();
                    if (SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                        // Skip TypeScript declaration files and minified JS
                        if (name.endsWith(".d.ts") || name.endsWith(".min.js")) return FileVisitResult.CONTINUE;
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
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
