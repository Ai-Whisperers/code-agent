package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import com.eneve.agent.agent.store.CodeGraphStore;

import java.util.Set;

/**
 * Tree-sitter driver for TypeScript ({@code .ts}, {@code .tsx}) files.
 *
 * <p>Emits graph nodes/edges for:
 * <ul>
 *   <li>Type declarations: {@code class_declaration}, {@code interface_declaration},
 *       {@code enum_declaration}, {@code type_alias_declaration}</li>
 *   <li>Function/method declarations: {@code method_definition},
 *       {@code function_declaration}, {@code arrow_function}</li>
 *   <li>Inheritance: {@code class_heritage} → {@code EXTENDS} / {@code IMPLEMENTS} edges</li>
 *   <li>Call expressions: {@code call_expression} → {@code CALLS} edges</li>
 *   <li>Import statements: {@code import_statement} → {@code IMPORTS} edges</li>
 * </ul>
 *
 * <p>TSX files use a separate Tree-sitter language ({@link Language#TSX}) but share
 * the same node type names for TypeScript constructs, so this driver handles both.
 */
public class TypeScriptDriver extends AbstractTreeSitterDriver {

    private static final Set<String> EXTENSIONS = Set.of("ts", "tsx");

    private final Language lang;

    /** Create a driver for {@code .ts} files. */
    public TypeScriptDriver() {
        this(Language.TYPESCRIPT);
    }

    /** Create a driver for a specific TypeScript variant (TYPESCRIPT or TSX). */
    TypeScriptDriver(Language lang) {
        this.lang = lang;
    }

    @Override
    public Language language() {
        return lang;
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public void index(Node root, String source, String wsName, String repoSlug,
                      String filePath, CodeGraphStore store) {
        if (root == null || root.isNull()) return;

        // Import statements
        walkDescendants(root, "import_statement", importNode -> {
            Node sourceNode = importNode.getChildByFieldName("source");
            if (sourceNode == null || sourceNode.isNull()) return;
            String module = stripQuotes(sourceNode.getContent());
            if (module == null || module.isBlank()) return;
            String simpleName = module.contains("/") ? module.substring(module.lastIndexOf('/') + 1) : module;
            store.upsertEdge(wsName, repoSlug, filePath, simpleName, "IMPORTS", filePath, null);
        });

        // Type declarations
        for (Node node : root) {
            String type = node.getType();
            if (type == null) continue;
            switch (type) {
                case "class_declaration" -> indexClassDecl(node, wsName, repoSlug, filePath, store);
                case "interface_declaration" -> indexInterfaceDecl(node, wsName, repoSlug, filePath, store);
                case "enum_declaration" -> indexEnumDecl(node, wsName, repoSlug, filePath, store);
                case "function_declaration" -> indexFunctionDecl(node, null, wsName, repoSlug, filePath, store);
                default -> { /* not a top-level declaration we handle */ }
            }
        }
    }

    private void indexClassDecl(Node classNode, String wsName, String repoSlug,
                                 String filePath, CodeGraphStore store) {
        String className = childText(classNode, "name");
        if (className == null || className.isBlank()) return;

        int lineNum = lineOf(classNode);
        emitTypeNode(store, wsName, repoSlug, filePath, className, "CLASS", lineNum, null);

        // Heritage: extends / implements
        Node heritage = classNode.getChildByFieldName("heritage");
        if (heritage != null && !heritage.isNull()) {
            for (Node clause : heritage.getChildren()) {
                String clauseType = clause.getType();
                if (clauseType == null) continue;
                if ("extends_clause".equals(clauseType)) {
                    Node typeNode = clause.getChildByFieldName("value");
                    if (typeNode != null && !typeNode.isNull()) {
                        String baseName = stripGenerics(typeNode.getContent());
                        if (baseName != null && !baseName.isBlank()) {
                            store.upsertEdge(wsName, repoSlug, className, baseName, "EXTENDS", filePath, null);
                        }
                    }
                } else if ("implements_clause".equals(clauseType)) {
                    for (Node impl : clause.getChildren()) {
                        if ("type_identifier".equals(impl.getType()) || "generic_type".equals(impl.getType())) {
                            String implName = stripGenerics(impl.getContent());
                            if (implName != null && !implName.isBlank()) {
                                store.upsertEdge(wsName, repoSlug, className, implName, "IMPLEMENTS", filePath, null);
                            }
                        }
                    }
                }
            }
        }

        // Methods within class body
        Node body = classNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;

        for (Node member : body) {
            if ("method_definition".equals(member.getType())) {
                indexMethodDefinition(member, className, wsName, repoSlug, filePath, store);
            }
        }
    }

    private void indexInterfaceDecl(Node ifaceNode, String wsName, String repoSlug,
                                     String filePath, CodeGraphStore store) {
        String ifaceName = childText(ifaceNode, "name");
        if (ifaceName == null || ifaceName.isBlank()) return;
        emitTypeNode(store, wsName, repoSlug, filePath, ifaceName, "INTERFACE", lineOf(ifaceNode), null);

        Node extendsClause = ifaceNode.getChildByFieldName("extends");
        if (extendsClause != null && !extendsClause.isNull()) {
            for (Node ext : extendsClause.getChildren()) {
                if ("type_identifier".equals(ext.getType())) {
                    store.upsertEdge(wsName, repoSlug, ifaceName, ext.getContent(), "EXTENDS", filePath, null);
                }
            }
        }
    }

    private void indexEnumDecl(Node enumNode, String wsName, String repoSlug,
                                String filePath, CodeGraphStore store) {
        String enumName = childText(enumNode, "name");
        if (enumName == null || enumName.isBlank()) return;
        emitTypeNode(store, wsName, repoSlug, filePath, enumName, "ENUM", lineOf(enumNode), null);
    }

    private void indexFunctionDecl(Node funcNode, String enclosingType, String wsName, String repoSlug,
                                    String filePath, CodeGraphStore store) {
        String funcName = childText(funcNode, "name");
        if (funcName == null || funcName.isBlank()) return;
        String qualifiedName = enclosingType != null ? enclosingType + "." + funcName : funcName;
        emitMethodNode(store, wsName, repoSlug, filePath, qualifiedName, lineOf(funcNode), null);
        indexCallsInBody(funcNode, qualifiedName, wsName, repoSlug, filePath, store);
    }

    private void indexMethodDefinition(Node methodNode, String enclosingType, String wsName, String repoSlug,
                                        String filePath, CodeGraphStore store) {
        String methodName = childText(methodNode, "name");
        if (methodName == null || methodName.isBlank()) return;
        String qualifiedName = enclosingType + "." + methodName;
        emitMethodNode(store, wsName, repoSlug, filePath, qualifiedName, lineOf(methodNode), null);
        indexCallsInBody(methodNode, qualifiedName, wsName, repoSlug, filePath, store);
    }

    private void indexCallsInBody(Node funcNode, String qualifiedName, String wsName, String repoSlug,
                                   String filePath, CodeGraphStore store) {
        Node body = funcNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;
        walkDescendants(body, "call_expression", callNode -> {
            Node funcRef = callNode.getChildByFieldName("function");
            if (funcRef == null || funcRef.isNull()) return;
            String callText = funcRef.getContent();
            if (callText == null || callText.isBlank()) return;
            emitCallEdge(store, wsName, repoSlug, filePath, qualifiedName, callText + "()");
        });
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripGenerics(String s) {
        if (s == null) return null;
        int lt = s.indexOf('<');
        return lt > 0 ? s.substring(0, lt).trim() : s.trim();
    }
}
