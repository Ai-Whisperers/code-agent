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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TypeScriptDriver}.
 */
class TypeScriptDriverTest {

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

    private static Node parseTs(String source) {
        try (Parser parser = Parser.getFor(Language.TYPESCRIPT)) {
            var tree = parser.parse(source);
            return tree != null ? tree.getRootNode() : null;
        }
    }

    @Test
    void emitsClassNodeAndMethodNode() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                class UserService {
                    getUser(id: string): User {
                        return this.repo.find(id);
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "UserService.ts", store);

        verify(store).upsertNode(eq("ws"), eq("repo"), eq("UserService.ts"),
                eq("UserService"), eq("CLASS"), anyInt(), any(), any());
        verify(store).upsertNode(eq("ws"), eq("repo"), eq("UserService.ts"),
                eq("UserService.getUser"), eq("METHOD"), anyInt(), any(), any());
    }

    @Test
    void emitsExtendsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                class AdminService extends UserService {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "AdminService.ts", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("AdminService"), eq("UserService"),
                eq("EXTENDS"), eq("AdminService.ts"), any());
    }

    @Test
    void emitsImplementsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                class OrderRepo implements IRepository {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "OrderRepo.ts", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("OrderRepo"), eq("IRepository"),
                eq("IMPLEMENTS"), eq("OrderRepo.ts"), any());
    }

    @Test
    void emitsCallsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                class Processor {
                    run(): void {
                        this.helper.execute();
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "Processor.ts", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"),
                eq("Processor.run"), target.capture(), eq("CALLS"), eq("Processor.ts"), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("execute")),
                "Expected CALLS edge to execute, got: " + target.getAllValues());
    }

    @Test
    void emitsInterfaceNode() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                interface IRepository {
                    find(id: string): any;
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "IRepository.ts", store);

        verify(store).upsertNode(eq("ws"), eq("repo"), eq("IRepository.ts"),
                eq("IRepository"), eq("INTERFACE"), anyInt(), any(), any());
    }

    @Test
    void emitsImportsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                import { Injectable } from '@angular/core';
                class MyService {}
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        TypeScriptDriver driver = new TypeScriptDriver();
        driver.index(parseTs(source), source, "ws", "repo", "MyService.ts", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"), any(),
                target.capture(), eq("IMPORTS"), any(), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("core")),
                "Expected IMPORTS edge for @angular/core, got: " + target.getAllValues());
    }
}
