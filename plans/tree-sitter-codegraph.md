# Plan: Replace Regex Parsing with Tree-sitter in CodeGraphIndexer

## Problem

`CodeGraphIndexer` currently supports 4 languages:

| Extension | Language | Mechanism |
|-----------|----------|-----------|
| `.java` | Java | JavaParser AST |
| `.cs` | C# | Regex |
| `.ts`, `.tsx` | TypeScript / TSX | Regex |
| `.php` | PHP | Regex |

The regex-based parsers for C#, TypeScript, and PHP are fragile — they rely on brace counting (`findApproximateMethodEnd`), keyword exclusion sets (`CS_KEYWORDS`, `TS_KEYWORDS`, `PHP_KEYWORDS`), and line-oriented patterns that break on valid but non-trivial code. Adding a new language requires writing and maintaining a new set of fragile patterns.

## Proposed Solution: Tree-sitter

[Tree-sitter](https://tree-sitter.github.io/tree-sitter/) is an incremental, error-tolerant parser used by GitHub, Neovim, Zed, and other code intelligence tools. It supports 100+ languages via separate grammar packages and produces a full Concrete Syntax Tree (CST) with well-defined, stable node types per language.

Java bindings are available on Maven Central via [jtreesitter](https://github.com/tree-sitter/jtreesitter).

### Why Tree-sitter over alternatives

| Property | Regex (current) | Tree-sitter | ANTLR4 |
|---|---|---|---|
| Languages supported | 4 | 100+ | ~100 (via grammars-v4) |
| Parsing accuracy | Fragile | Full CST | Full parse tree |
| Handles malformed code | Often breaks | Graceful recovery | Often fails |
| Incremental re-parse | No | Yes | No |
| Pure Java | Yes | No (JNI, native bundled) | Yes |
| Effort to add a language | High (new regex set) | Low (add 1 Maven dep) | Medium (include grammar jar) |

Tree-sitter is preferred over ANTLR4 because adding a new language is a single Maven dependency with no grammar compilation step, and it gracefully handles the malformed / partially written code common in real repositories.

**Keep JavaParser for `.java`** — it provides a fully-typed AST with symbol resolution, which is stronger than Tree-sitter for Java specifically.

## Implementation Plan

### 1. Add Maven dependencies

```xml
<!-- Tree-sitter Java binding -->
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>jtreesitter</artifactId>
    <version>0.24.0</version>
</dependency>

<!-- Language grammars -->
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter-typescript</artifactId>
    <version>0.23.2</version>
</dependency>
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter-c-sharp</artifactId>
    <version>0.23.1</version>
</dependency>
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter-php</artifactId>
    <version>0.23.1</version>
</dependency>
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter-python</artifactId>
    <version>0.23.2</version>
</dependency>
```

### 2. Create a `TreeSitterLanguageDriver` abstraction

Introduce a `TreeSitterLanguageDriver` interface (or abstract class) so each language is a self-contained class rather than a method in the monolithic `CodeGraphIndexer`:

```java
interface TreeSitterLanguageDriver {
    Language language();
    Set<String> extensions();
    void index(Node root, String source, String repoId, String filePath, CodeGraphStore store);
}
```

Implementations: `CSharpDriver`, `TypeScriptDriver`, `PhpDriver`, `PythonDriver`, `GoDriver`, etc.

### 3. Replace `dispatchIndex` in `CodeGraphIndexer`

- Keep `indexJavaFile` (JavaParser).
- Remove `indexCSharpFile`, `indexTypeScriptFile`, `indexPhpFile`.
- Register all `TreeSitterLanguageDriver` implementations in a `Map<String, TreeSitterLanguageDriver>` keyed by file extension.
- `dispatchIndex` looks up the driver by extension and delegates, or falls back to JavaParser for `.java`.

### 4. Expand `SUPPORTED_EXTENSIONS`

Add extensions for each new grammar added:

```
.py, .go, .rs, .kt, .kts, .rb, .swift, .cpp, .c, .h
```

### 5. Update `CodeGraphQueryService`

The `buildImpactSection` method currently filters only `.java` and `.cs` files. Expand this to include all indexed extensions once the drivers are in place.

## Node type reference per language

Use the [Tree-sitter Playground](https://ts-playground.vercel.app) to inspect the CST for any language snippet and prototype queries before implementing a driver.

| Language | Key node types |
|----------|----------------|
| TypeScript | `class_declaration`, `method_definition`, `import_statement`, `call_expression` |
| C# | `class_declaration`, `method_declaration`, `using_directive`, `invocation_expression` |
| PHP | `class_declaration`, `method_declaration`, `namespace_use_declaration`, `function_call_expression` |
| Python | `class_definition`, `function_definition`, `import_statement`, `call` |
| Go | `type_spec`, `function_declaration`, `method_declaration`, `import_declaration`, `call_expression` |
| Rust | `struct_item`, `impl_item`, `fn_item`, `use_declaration`, `call_expression` |
| Kotlin | `class_declaration`, `function_declaration`, `import_directive`, `call_expression` |

## Caveats

- **JNI / native binaries**: `jtreesitter` ships pre-built native libraries for `linux/amd64`, `linux/aarch64`, `darwin/arm64`, and `windows/amd64`. Works out of the box for JVM deployments. If a Quarkus native image build is ever added, native library inclusion will need to be configured.
- **JavaParser stays**: Tree-sitter's Java grammar is less feature-rich than JavaParser for symbol resolution. Keep JavaParser for `.java` files.
- **File size cap**: The existing 200 KB file size guard in `indexFull` is still valid and should be kept.
