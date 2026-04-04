package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import com.eneve.agent.agent.service.BedrockEmbeddingService;
import com.eneve.agent.agent.store.EmbeddingStore;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.StaticJavaParser;
import com.eneve.agent.util.JavaParserConfig;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.eneve.agent.workspace.WorkspaceContext;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Extracts symbol source code from repositories and generates vector embeddings
 * via Voyage AI for semantic code search.
 */
@ApplicationScoped
public class EmbeddingIndexer {

    static {
        StaticJavaParser.setConfiguration(JavaParserConfig.java21BaseConfiguration());
    }

    private static final Logger LOG = Logger.getLogger(EmbeddingIndexer.class);
    private static final long MAX_FILE_SIZE = 200 * 1024; // 200KB
    private static final long MAX_INDEX_TIME_MS = 120_000; // 2 minutes
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".cs", ".ts", ".tsx", ".php");

    private static final Pattern CS_TYPE_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|internal|static|abstract|sealed|partial|readonly|new)\\s+)*"
                    + "(?:class|interface|struct|enum|record)\\s+"
                    + "(?<name>\\w+)",
            Pattern.MULTILINE);

    private static final Pattern CS_METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|new|sealed|partial)\\s+)*"
                    + "(?:[\\w<>\\[\\],\\s?]+?)\\s+"
                    + "(?<name>\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Set<String> CS_KEYWORDS = Set.of(
            "if", "else", "for", "foreach", "while", "do", "switch", "case", "return",
            "try", "catch", "finally", "throw", "using", "lock", "yield", "await",
            "var", "new", "typeof", "sizeof", "nameof", "default", "checked", "unchecked",
            "this", "base", "null", "true", "false", "void", "string", "int", "long",
            "bool", "double", "float", "decimal", "byte", "char", "short", "object",
            "get", "set", "value", "namespace", "class", "interface", "struct", "enum", "record");

    // ── TypeScript patterns ────────────────────────────────────────────
    private static final Pattern TS_TYPE_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?(?:class|interface|enum|type)\\s+(?<name>\\w+)",
            Pattern.MULTILINE);

    private static final Pattern TS_METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|async|readonly|abstract|override)\\s+)*"
                    + "(?<name>\\w+)\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::\\s*[\\w<>\\[\\]|&?,\\s]+?)?\\s*(?:\\{|=>)",
            Pattern.MULTILINE);

    private static final Pattern TS_FUNCTION_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(?<name>\\w+)\\s*(?:<[^>]*>)?\\s*\\(",
            Pattern.MULTILINE);

    private static final Set<String> TS_KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
            "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof", "in", "of",
            "this", "super", "null", "undefined", "true", "false", "void", "let", "const", "var",
            "async", "await", "yield", "from", "import", "export", "default", "class", "extends",
            "implements", "interface", "type", "enum", "namespace", "module", "declare", "abstract",
            "get", "set", "static", "public", "private", "protected", "readonly", "override",
            "console", "Math", "Object", "Array", "String", "Number", "Boolean", "Promise",
            "require", "constructor");

    // ── PHP patterns ───────────────────────────────────────────────────
    private static final Pattern PHP_TYPE_DECL = Pattern.compile(
            "^\\s*(?:(?:abstract|final|readonly)\\s+)*(?:class|interface|trait|enum)\\s+(?<name>\\w+)",
            Pattern.MULTILINE);

    private static final Pattern PHP_METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|abstract|final)\\s+)*function\\s+(?<name>\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Set<String> PHP_KEYWORDS = Set.of(
            "if", "else", "elseif", "for", "foreach", "while", "do", "switch", "case", "return",
            "break", "continue", "try", "catch", "finally", "throw", "new", "clone", "echo", "print",
            "isset", "empty", "unset", "list", "array", "null", "true", "false", "self", "parent",
            "static", "abstract", "final", "class", "interface", "trait", "enum", "extends",
            "implements", "namespace", "use", "require", "require_once", "include", "include_once",
            "match", "fn", "yield", "this", "string", "int", "float", "bool", "void", "mixed");

    @Inject BedrockEmbeddingService bedrockService;
    @Inject EmbeddingStore embeddingStore;
    @Inject com.eneve.agent.settings.SettingsService settings;

    record SymbolChunk(String filePath, String symbolName, String symbolType,
                       Integer lineStart, Integer lineEnd, String sourceText) {}

    public void indexFull(WorkspaceContext workspace, String wsName, String repoSlug) {
        if (!bedrockService.isConfigured()) {
            LOG.debug("Bedrock embedding not configured — skipping embedding index");
            return;
        }

        LOG.infof("Full embedding index for %s/%s", wsName, repoSlug);
        embeddingStore.deleteAllForRepo(wsName, repoSlug);

        List<Path> sourceFiles = findSourceFiles(workspace.getRoot());
        LOG.infof("Found %d source files for embedding (Java, C#, TypeScript, PHP)", sourceFiles.size());

        List<SymbolChunk> allChunks = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (Path file : sourceFiles) {
            if (System.currentTimeMillis() - startTime > MAX_INDEX_TIME_MS) {
                LOG.warnf("Embedding index time limit reached after %d chunks", allChunks.size());
                break;
            }
            String relativePath = workspace.getRoot().relativize(file).toString();
            allChunks.addAll(extractChunks(file, relativePath));
        }

        if (allChunks.isEmpty()) {
            LOG.infof("No symbols extracted for %s/%s — nothing to embed", wsName, repoSlug);
            return;
        }

        LOG.infof("Extracted %d symbol chunks, generating embeddings...", allChunks.size());
        List<String> texts = allChunks.stream().map(SymbolChunk::sourceText).toList();
        List<float[]> embeddings = bedrockService.embed(texts, "document");

        if (embeddings.size() != allChunks.size()) {
            LOG.warnf("Embedding count mismatch: %d chunks but %d embeddings", allChunks.size(), embeddings.size());
        }

        int stored = 0;
        for (int i = 0; i < Math.min(allChunks.size(), embeddings.size()); i++) {
            float[] vec = embeddings.get(i);
            if (vec == null) continue;

            SymbolChunk chunk = allChunks.get(i);
            embeddingStore.upsertEmbedding(wsName, repoSlug, chunk.filePath(),
                    chunk.symbolName(), chunk.symbolType(), chunk.sourceText(),
                    chunk.lineStart(), chunk.lineEnd(), vec);
            stored++;
        }

        LOG.infof("Embedding index complete: %d embeddings stored for %s/%s", stored, wsName, repoSlug);
    }

    public void indexIncremental(WorkspaceContext workspace, String wsName, String repoSlug,
                                 List<String> changedFiles) {
        if (!bedrockService.isConfigured()) {
            return;
        }

        List<String> indexable = changedFiles.stream()
                .filter(f -> SUPPORTED_EXTENSIONS.stream().anyMatch(f::endsWith))
                .toList();

        if (indexable.isEmpty()) {
            return;
        }

        LOG.infof("Incremental embedding index for %s/%s: %d files", wsName, repoSlug, indexable.size());
        List<SymbolChunk> allChunks = new ArrayList<>();

        for (String filePath : indexable) {
            embeddingStore.deleteForFile(wsName, repoSlug, filePath);
            Path absolutePath = workspace.getRoot().resolve(filePath);
            if (Files.exists(absolutePath)) {
                allChunks.addAll(extractChunks(absolutePath, filePath));
            }
        }

        if (allChunks.isEmpty()) {
            return;
        }

        List<String> texts = allChunks.stream().map(SymbolChunk::sourceText).toList();
        List<float[]> embeddings = bedrockService.embed(texts, "document");

        int stored = 0;
        for (int i = 0; i < Math.min(allChunks.size(), embeddings.size()); i++) {
            float[] vec = embeddings.get(i);
            if (vec == null) continue;

            SymbolChunk chunk = allChunks.get(i);
            embeddingStore.upsertEmbedding(wsName, repoSlug, chunk.filePath(),
                    chunk.symbolName(), chunk.symbolType(), chunk.sourceText(),
                    chunk.lineStart(), chunk.lineEnd(), vec);
            stored++;
        }

        LOG.infof("Incremental embedding index complete: %d embeddings stored for %s/%s",
                stored, wsName, repoSlug);
    }

    // ── Chunk extraction ────────────────────────────────────────────────

    private List<SymbolChunk> extractChunks(Path file, String relativePath) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return List.of();
            }
            if (relativePath.endsWith(".java")) {
                return extractJavaChunks(file, relativePath);
            } else if (relativePath.endsWith(".cs")) {
                return extractCSharpChunks(file, relativePath);
            } else if (relativePath.endsWith(".ts") || relativePath.endsWith(".tsx")) {
                return extractTypeScriptChunks(file, relativePath);
            } else if (relativePath.endsWith(".php")) {
                return extractPhpChunks(file, relativePath);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract chunks from %s: %s", relativePath, e.getMessage());
        }
        return List.of();
    }

    private List<SymbolChunk> extractJavaChunks(Path file, String relativePath) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(file);
        List<SymbolChunk> chunks = new ArrayList<>();

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String name = decl.getNameAsString();
            String type = decl.isInterface() ? "INTERFACE" : "CLASS";
            int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);
            String source = truncateSource(decl.toString());

            chunks.add(new SymbolChunk(relativePath, name, type, lineStart, lineEnd, source));

            for (MethodDeclaration method : decl.getMethods()) {
                String methodName = name + "." + method.getNameAsString();
                int mStart = method.getBegin().map(p -> p.line).orElse(0);
                int mEnd = method.getEnd().map(p -> p.line).orElse(0);
                String methodSource = truncateSource(method.toString());

                chunks.add(new SymbolChunk(relativePath, methodName, "METHOD", mStart, mEnd, methodSource));
            }
        }

        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            String name = decl.getNameAsString();
            int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
            int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);
            String source = truncateSource(decl.toString());
            chunks.add(new SymbolChunk(relativePath, name, "ENUM", lineStart, lineEnd, source));
        }

        return chunks;
    }

    private List<SymbolChunk> extractCSharpChunks(Path file, String relativePath) throws IOException {
        String source = Files.readString(file);
        List<SymbolChunk> chunks = new ArrayList<>();
        String currentType = null;

        Matcher typeMatcher = CS_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String name = typeMatcher.group("name");
            int lineStart = lineNumberAt(source, typeMatcher.start());
            int bodyEnd = findClosingBrace(source, typeMatcher.end());
            String typeSource = truncateSource(source.substring(typeMatcher.start(),
                    Math.min(bodyEnd + 1, source.length())));

            chunks.add(new SymbolChunk(relativePath, name, "CLASS", lineStart,
                    lineNumberAt(source, bodyEnd), typeSource));

            if (currentType == null) {
                currentType = name;
            }
        }

        if (currentType != null) {
            String enclosing = currentType;
            Matcher methodMatcher = CS_METHOD_DECL.matcher(source);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group("name");
                if (CS_KEYWORDS.contains(methodName)) continue;

                int lineStart = lineNumberAt(source, methodMatcher.start());
                int bodyEnd = findClosingBrace(source, methodMatcher.end());
                String methodSource = truncateSource(source.substring(methodMatcher.start(),
                        Math.min(bodyEnd + 1, source.length())));

                chunks.add(new SymbolChunk(relativePath, enclosing + "." + methodName, "METHOD",
                        lineStart, lineNumberAt(source, bodyEnd), methodSource));
            }
        }

        return chunks;
    }

    private List<SymbolChunk> extractTypeScriptChunks(Path file, String relativePath) throws IOException {
        String source = Files.readString(file);
        List<SymbolChunk> chunks = new ArrayList<>();
        String currentType = null;

        Matcher typeMatcher = TS_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String name = typeMatcher.group("name");
            int lineStart = lineNumberAt(source, typeMatcher.start());
            int bodyEnd = findClosingBrace(source, typeMatcher.end());
            String typeSource = truncateSource(source.substring(typeMatcher.start(),
                    Math.min(bodyEnd + 1, source.length())));

            chunks.add(new SymbolChunk(relativePath, name, "CLASS", lineStart,
                    lineNumberAt(source, bodyEnd), typeSource));

            if (currentType == null) {
                currentType = name;
            }
        }

        // Top-level functions
        Matcher funcMatcher = TS_FUNCTION_DECL.matcher(source);
        while (funcMatcher.find()) {
            String funcName = funcMatcher.group("name");
            if (TS_KEYWORDS.contains(funcName)) continue;
            int lineStart = lineNumberAt(source, funcMatcher.start());
            int bodyEnd = findClosingBrace(source, funcMatcher.end());
            String funcSource = truncateSource(source.substring(funcMatcher.start(),
                    Math.min(bodyEnd + 1, source.length())));
            chunks.add(new SymbolChunk(relativePath, funcName, "METHOD", lineStart,
                    lineNumberAt(source, bodyEnd), funcSource));
        }

        if (currentType != null) {
            String enclosing = currentType;
            Matcher methodMatcher = TS_METHOD_DECL.matcher(source);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group("name");
                if (TS_KEYWORDS.contains(methodName)) continue;

                int lineStart = lineNumberAt(source, methodMatcher.start());
                // TS_METHOD_DECL ends with '{', so the brace is already consumed
                int bodyBrace = methodMatcher.end() > 0 && source.charAt(methodMatcher.end() - 1) == '{'
                        ? methodMatcher.end() - 1
                        : source.indexOf('{', methodMatcher.end());
                if (bodyBrace < 0) continue;

                int bodyEnd = findClosingBrace(source, bodyBrace);
                String methodSource = truncateSource(source.substring(methodMatcher.start(),
                        Math.min(bodyEnd + 1, source.length())));

                chunks.add(new SymbolChunk(relativePath, enclosing + "." + methodName, "METHOD",
                        lineStart, lineNumberAt(source, bodyEnd), methodSource));
            }
        }

        return chunks;
    }

    private List<SymbolChunk> extractPhpChunks(Path file, String relativePath) throws IOException {
        String source = Files.readString(file);
        List<SymbolChunk> chunks = new ArrayList<>();
        String currentType = null;

        Matcher typeMatcher = PHP_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            String name = typeMatcher.group("name");
            int lineStart = lineNumberAt(source, typeMatcher.start());
            int bodyEnd = findClosingBrace(source, typeMatcher.end());
            String typeSource = truncateSource(source.substring(typeMatcher.start(),
                    Math.min(bodyEnd + 1, source.length())));

            chunks.add(new SymbolChunk(relativePath, name, "CLASS", lineStart,
                    lineNumberAt(source, bodyEnd), typeSource));

            if (currentType == null) {
                currentType = name;
            }
        }

        if (currentType != null) {
            String enclosing = currentType;
            Matcher methodMatcher = PHP_METHOD_DECL.matcher(source);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group("name");
                if (PHP_KEYWORDS.contains(methodName)) continue;

                int lineStart = lineNumberAt(source, methodMatcher.start());
                int bodyEnd = findClosingBrace(source, methodMatcher.end());
                String methodSource = truncateSource(source.substring(methodMatcher.start(),
                        Math.min(bodyEnd + 1, source.length())));

                chunks.add(new SymbolChunk(relativePath, enclosing + "." + methodName, "METHOD",
                        lineStart, lineNumberAt(source, bodyEnd), methodSource));
            }
        }

        return chunks;
    }

    private String truncateSource(String source) {
        int maxChars = Integer.parseInt(settings.get("embedding.max-source-chars", "16000"));
        if (source.length() <= maxChars) {
            return source;
        }
        return source.substring(0, maxChars) + "\n// ... truncated";
    }

    private static int lineNumberAt(String source, int charOffset) {
        int line = 1;
        for (int i = 0; i < charOffset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static int findClosingBrace(String source, int fromIndex) {
        int depth = 0;
        boolean inBody = false;
        for (int i = fromIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') { depth++; inBody = true; }
            else if (c == '}') {
                depth--;
                if (inBody && depth == 0) return i;
            }
        }
        return Math.min(fromIndex + 5000, source.length() - 1);
    }

    // ── File discovery ───────────────────────────────────────────────────

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
            LOG.warnf("Error walking file tree for embedding: %s", e.getMessage());
        }
        return files;
    }
}
