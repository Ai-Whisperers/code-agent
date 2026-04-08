package com.eneve.agent.agent.treesitter;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.LibraryLoader;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import com.eneve.agent.agent.store.CodeGraphStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CSharpDriver}.
 *
 * <p>Tests are skipped automatically when the Tree-sitter native library is
 * unavailable (e.g. unsupported CI platform).
 */
class CSharpDriverTest {

    private static boolean treeSitterAvailable = false;

    @BeforeAll
    static void loadNative() {
        try {
            LibraryLoader.load();
            treeSitterAvailable = true;
        } catch (Throwable t) {
            // Native library not available — tests will be skipped
        }
    }

    private static Node parseCs(String source) {
        try (Parser parser = Parser.getFor(Language.C_SHARP)) {
            var tree = parser.parse(source);
            return tree != null ? tree.getRootNode() : null;
        }
    }

    @Test
    void emitsClassNodeAndMethodNode() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                public class MyService {
                    public void DoWork() {
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        Node root = parseCs(source);
        assertNotNull(root, "Parse should succeed");

        driver.index(root, source, "ws", "repo", "MyService.cs", store);

        // CLASS node
        verify(store).upsertNode(eq("ws"), eq("repo"), eq("MyService.cs"),
                eq("MyService"), eq("CLASS"), anyInt(), any(), any());
        // METHOD node
        verify(store).upsertNode(eq("ws"), eq("repo"), eq("MyService.cs"),
                eq("MyService.DoWork"), eq("METHOD"), anyInt(), any(), any());
    }

    @Test
    void emitsExtendsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                public class Dog : Animal {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "Dog.cs", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("Dog"), eq("Animal"),
                eq("EXTENDS"), eq("Dog.cs"), any());
    }

    @Test
    void emitsImplementsEdgeForInterface() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                public class MyRepo : IRepository {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "MyRepo.cs", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("MyRepo"), eq("IRepository"),
                eq("IMPLEMENTS"), eq("MyRepo.cs"), any());
    }

    @Test
    void emitsCallsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                public class Caller {
                    public void Run() {
                        helper.Execute();
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "Caller.cs", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"),
                eq("Caller.Run"), target.capture(), eq("CALLS"), eq("Caller.cs"), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("Execute")),
                "Expected CALLS edge to Execute, got: " + target.getAllValues());
    }

    @Test
    void emitsImportsEdgeForUsingDirective() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                using System.Collections.Generic;
                public class Foo {}
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "Foo.cs", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"), any(),
                target.capture(), eq("IMPORTS"), any(), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("Generic")),
                "Expected IMPORTS edge for Generic, got: " + target.getAllValues());
    }

    @Test
    void handlesFileScopedNamespace() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        // File-scoped namespace (C# 10+) — no braces
        String source = """
                namespace MyApp.Services;
                public class OrderService {
                    public void Process() {}
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "OrderService.cs", store);

        verify(store).upsertNode(eq("ws"), eq("repo"), eq("OrderService.cs"),
                eq("OrderService"), eq("CLASS"), anyInt(), any(), any());
    }

    @Test
    void handlesMultipleTypesInOneFile() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                public class Alpha {
                    public void MethodA() {}
                }
                public interface IBeta {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        CSharpDriver driver = new CSharpDriver();
        driver.index(parseCs(source), source, "ws", "repo", "Multi.cs", store);

        verify(store).upsertNode(eq("ws"), eq("repo"), eq("Multi.cs"),
                eq("Alpha"), eq("CLASS"), anyInt(), any(), any());
        verify(store).upsertNode(eq("ws"), eq("repo"), eq("Multi.cs"),
                eq("IBeta"), eq("INTERFACE"), anyInt(), any(), any());
    }
}
