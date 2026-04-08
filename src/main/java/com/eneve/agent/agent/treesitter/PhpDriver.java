package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import com.eneve.agent.agent.store.CodeGraphStore;

import java.util.Set;

/**
 * Tree-sitter driver for PHP ({@code .php}) files.
 *
 * <p>Emits graph nodes/edges for:
 * <ul>
 *   <li>Type declarations: {@code class_declaration}, {@code interface_declaration},
 *       {@code trait_declaration}, {@code enum_declaration}</li>
 *   <li>Method declarations: {@code method_declaration}</li>
 *   <li>Inheritance: {@code base_clause} → {@code EXTENDS} edge;
 *       {@code class_implements} → {@code IMPLEMENTS} edges</li>
 *   <li>Function calls: {@code function_call_expression},
 *       {@code member_call_expression} → {@code CALLS} edges</li>
 *   <li>Namespace use declarations: {@code namespace_use_declaration} → {@code IMPORTS} edges</li>
 * </ul>
 */
public class PhpDriver extends AbstractTreeSitterDriver {

    private static final Set<String> EXTENSIONS = Set.of("php");

    @Override
    public Language language() {
        return Language.PHP;
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public void index(Node root, String source, String wsName, String repoSlug,
                      String filePath, CodeGraphStore store) {
        if (root == null || root.isNull()) return;

        // Namespace use declarations (imports)
        walkDescendants(root, "namespace_use_declaration", useNode -> {
            for (Node clause : useNode.getChildren()) {
                if ("namespace_use_clause".equals(clause.getType())) {
                    Node nameNode = clause.getChildByFieldName("name");
                    if (nameNode == null || nameNode.isNull()) {
                        // fall back to first child content
                        nameNode = clause.getChildCount() > 0 ? clause.getChild(0) : null;
                    }
                    if (nameNode == null || nameNode.isNull()) continue;
                    String fqn = nameNode.getContent();
                    if (fqn == null || fqn.isBlank()) continue;
                    String simpleName = fqn.contains("\\") ? fqn.substring(fqn.lastIndexOf('\\') + 1) : fqn;
                    store.upsertEdge(wsName, repoSlug, filePath, simpleName, "IMPORTS", filePath, null);
                }
            }
        });

        // Type declarations
        for (Node node : root) {
            String type = node.getType();
            if (type == null) continue;
            switch (type) {
                case "class_declaration" -> indexClassDecl(node, "CLASS", wsName, repoSlug, filePath, store);
                case "interface_declaration" -> indexClassDecl(node, "INTERFACE", wsName, repoSlug, filePath, store);
                case "trait_declaration" -> indexClassDecl(node, "CLASS", wsName, repoSlug, filePath, store);
                case "enum_declaration" -> indexClassDecl(node, "ENUM", wsName, repoSlug, filePath, store);
                default -> { /* not a type declaration */ }
            }
        }
    }

    private void indexClassDecl(Node classNode, String symbolType, String wsName, String repoSlug,
                                 String filePath, CodeGraphStore store) {
        String className = childText(classNode, "name");
        if (className == null || className.isBlank()) return;

        int lineNum = lineOf(classNode);
        emitTypeNode(store, wsName, repoSlug, filePath, className, symbolType, lineNum, null);

        // Extends (single class)
        Node baseClause = classNode.getChildByFieldName("base_clause");
        if (baseClause != null && !baseClause.isNull()) {
            String baseName = extractQualifiedName(baseClause);
            if (baseName != null && !baseName.isBlank()) {
                store.upsertEdge(wsName, repoSlug, className, baseName, "EXTENDS", filePath, null);
            }
        }

        // Implements (multiple interfaces)
        Node implClause = classNode.getChildByFieldName("class_implements");
        if (implClause != null && !implClause.isNull()) {
            for (Node child : implClause.getChildren()) {
                String childType = child.getType();
                if ("named_type".equals(childType) || "qualified_name".equals(childType)
                        || "name".equals(childType)) {
                    String implName = child.getContent();
                    if (implName != null && !implName.isBlank()) {
                        store.upsertEdge(wsName, repoSlug, className, implName, "IMPLEMENTS", filePath, null);
                    }
                }
            }
        }

        // Methods within class body
        Node body = classNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;

        for (Node member : body) {
            if ("method_declaration".equals(member.getType())) {
                indexMethodDecl(member, className, wsName, repoSlug, filePath, store);
            }
        }
    }

    private void indexMethodDecl(Node methodNode, String enclosingType, String wsName, String repoSlug,
                                  String filePath, CodeGraphStore store) {
        String methodName = childText(methodNode, "name");
        if (methodName == null || methodName.isBlank()) return;

        String qualifiedName = enclosingType + "." + methodName;
        int lineNum = lineOf(methodNode);
        emitMethodNode(store, wsName, repoSlug, filePath, qualifiedName, lineNum, null);

        // Function calls within method body
        Node body = methodNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;

        walkDescendants(body, "function_call_expression", callNode -> {
            Node funcNode = callNode.getChildByFieldName("function");
            if (funcNode == null || funcNode.isNull()) return;
            String callText = funcNode.getContent();
            if (callText != null && !callText.isBlank()) {
                emitCallEdge(store, wsName, repoSlug, filePath, qualifiedName, callText + "()");
            }
        });

        walkDescendants(body, "member_call_expression", callNode -> {
            Node nameNode = callNode.getChildByFieldName("name");
            Node objNode = callNode.getChildByFieldName("object");
            if (nameNode == null || nameNode.isNull()) return;
            String obj = (objNode != null && !objNode.isNull()) ? objNode.getContent() : null;
            String method = nameNode.getContent();
            if (method != null && !method.isBlank()) {
                String target = obj != null ? obj + "." + method + "()" : method + "()";
                emitCallEdge(store, wsName, repoSlug, filePath, qualifiedName, target);
            }
        });
    }

    private static String extractQualifiedName(Node node) {
        if (node == null || node.isNull()) return null;
        String content = node.getContent();
        if (content == null) return null;
        // Strip leading backslash (fully-qualified PHP names)
        content = content.trim();
        if (content.startsWith("\\")) content = content.substring(1);
        return content.contains("\\") ? content.substring(content.lastIndexOf('\\') + 1) : content;
    }
}
