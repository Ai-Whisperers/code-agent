package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import com.eneve.agent.agent.store.CodeGraphStore;

import java.util.Set;

/**
 * Tree-sitter driver for C# ({@code .cs}) files.
 *
 * <p>Emits graph nodes/edges for:
 * <ul>
 *   <li>Type declarations: {@code class_declaration}, {@code interface_declaration},
 *       {@code struct_declaration}, {@code enum_declaration}, {@code record_declaration}</li>
 *   <li>Method declarations: {@code method_declaration}, {@code constructor_declaration}</li>
 *   <li>Inheritance: {@code base_list} → {@code EXTENDS} / {@code IMPLEMENTS} edges</li>
 *   <li>Invocations: {@code invocation_expression} → {@code CALLS} edges</li>
 *   <li>Using directives: {@code using_directive} → {@code IMPORTS} edges</li>
 * </ul>
 *
 * <p>Handles file-scoped namespaces and multiple top-level type declarations per file
 * correctly because Tree-sitter parses the full CST rather than relying on line-oriented
 * regex patterns.
 */
public class CSharpDriver extends AbstractTreeSitterDriver {

    private static final Set<String> EXTENSIONS = Set.of("cs");

    @Override
    public Language language() {
        return Language.C_SHARP;
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public void index(Node root, String source, String wsName, String repoSlug,
                      String filePath, CodeGraphStore store) {
        if (root == null || root.isNull()) return;

        // Using directives — emit IMPORTS edges (source retroactively updated when first type is found)
        walkDescendants(root, "using_directive", usingNode -> {
            Node nameNode = usingNode.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) return;
            String ns = nameNode.getContent();
            if (ns == null || ns.isBlank()) return;
            String simpleName = ns.contains(".") ? ns.substring(ns.lastIndexOf('.') + 1) : ns;
            store.upsertEdge(wsName, repoSlug, filePath, simpleName, "IMPORTS", filePath, null);
        });

        // Type declarations
        for (Node node : root) {
            String type = node.getType();
            if (type == null) continue;
            switch (type) {
                case "class_declaration" -> indexTypeDecl(node, "CLASS", wsName, repoSlug, filePath, store);
                case "interface_declaration" -> indexTypeDecl(node, "INTERFACE", wsName, repoSlug, filePath, store);
                case "struct_declaration" -> indexTypeDecl(node, "CLASS", wsName, repoSlug, filePath, store);
                case "record_declaration" -> indexTypeDecl(node, "CLASS", wsName, repoSlug, filePath, store);
                case "enum_declaration" -> indexTypeDecl(node, "ENUM", wsName, repoSlug, filePath, store);
                default -> { /* not a type declaration */ }
            }
        }
    }

    private void indexTypeDecl(Node typeNode, String symbolType, String wsName, String repoSlug,
                                String filePath, CodeGraphStore store) {
        String typeName = childText(typeNode, "name");
        if (typeName == null || typeName.isBlank()) return;

        int lineNum = lineOf(typeNode);
        String modifiers = extractModifiers(typeNode);
        emitTypeNode(store, wsName, repoSlug, filePath, typeName, symbolType, lineNum, modifiers);

        // Base list — EXTENDS / IMPLEMENTS edges
        Node baseList = typeNode.getChildByFieldName("bases");
        if (baseList != null && !baseList.isNull()) {
            boolean first = true;
            for (Node baseNode : baseList) {
                String baseType = baseNode.getType();
                if (baseType == null || baseType.equals(",") || baseType.equals(":")) continue;
                String baseName = baseNode.getContent();
                if (baseName == null || baseName.isBlank()) continue;
                // Strip generic type arguments
                int lt = baseName.indexOf('<');
                if (lt > 0) baseName = baseName.substring(0, lt).trim();
                if (first && !"INTERFACE".equals(symbolType) && !"ENUM".equals(symbolType)) {
                    store.upsertEdge(wsName, repoSlug, typeName, baseName, "EXTENDS", filePath, null);
                } else {
                    store.upsertEdge(wsName, repoSlug, typeName, baseName, "IMPLEMENTS", filePath, null);
                }
                first = false;
            }
        }

        // Method and constructor declarations within this type
        Node body = typeNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;

        for (Node member : body) {
            String memberType = member.getType();
            if (memberType == null) continue;
            if ("method_declaration".equals(memberType) || "constructor_declaration".equals(memberType)) {
                indexMethodDecl(member, typeName, wsName, repoSlug, filePath, store);
            }
        }
    }

    private void indexMethodDecl(Node methodNode, String enclosingType, String wsName, String repoSlug,
                                  String filePath, CodeGraphStore store) {
        String methodName = childText(methodNode, "name");
        if (methodName == null || methodName.isBlank()) return;

        String qualifiedName = enclosingType + "." + methodName;
        int lineNum = lineOf(methodNode);
        String modifiers = extractModifiers(methodNode);
        emitMethodNode(store, wsName, repoSlug, filePath, qualifiedName, lineNum, modifiers);

        // Invocations within this method body
        Node body = methodNode.getChildByFieldName("body");
        if (body == null || body.isNull()) return;

        walkDescendants(body, "invocation_expression", invNode -> {
            Node funcNode = invNode.getChildByFieldName("function");
            if (funcNode == null || funcNode.isNull()) return;
            String callText = funcNode.getContent();
            if (callText == null || callText.isBlank()) return;
            emitCallEdge(store, wsName, repoSlug, filePath, qualifiedName, callText + "()");
        });
    }

    private static String extractModifiers(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node.getChildren()) {
            String t = child.getType();
            if (t != null && (t.endsWith("_modifier") || t.equals("modifier"))) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(child.getContent());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
