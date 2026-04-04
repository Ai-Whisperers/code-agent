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
 * Unit tests for {@link PhpDriver}.
 */
class PhpDriverTest {

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

    private static Node parsePhp(String source) {
        try (Parser parser = Parser.getFor(Language.PHP)) {
            var tree = parser.parse(source);
            return tree != null ? tree.getRootNode() : null;
        }
    }

    @Test
    void emitsClassNodeAndMethodNode() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                <?php
                class OrderService {
                    public function process() {
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        PhpDriver driver = new PhpDriver();
        driver.index(parsePhp(source), source, "ws", "repo", "OrderService.php", store);

        verify(store).upsertNode(eq("ws"), eq("repo"), eq("OrderService.php"),
                eq("OrderService"), eq("CLASS"), anyInt(), any(), any());
        verify(store).upsertNode(eq("ws"), eq("repo"), eq("OrderService.php"),
                eq("OrderService.process"), eq("METHOD"), anyInt(), any(), any());
    }

    @Test
    void emitsExtendsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                <?php
                class AdminController extends BaseController {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        PhpDriver driver = new PhpDriver();
        driver.index(parsePhp(source), source, "ws", "repo", "AdminController.php", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("AdminController"), eq("BaseController"),
                eq("EXTENDS"), eq("AdminController.php"), any());
    }

    @Test
    void emitsImplementsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                <?php
                class UserRepo implements RepositoryInterface {
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        PhpDriver driver = new PhpDriver();
        driver.index(parsePhp(source), source, "ws", "repo", "UserRepo.php", store);

        verify(store).upsertEdge(eq("ws"), eq("repo"), eq("UserRepo"), eq("RepositoryInterface"),
                eq("IMPLEMENTS"), eq("UserRepo.php"), any());
    }

    @Test
    void emitsCallsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                <?php
                class Processor {
                    public function run() {
                        doSomething();
                    }
                }
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        PhpDriver driver = new PhpDriver();
        driver.index(parsePhp(source), source, "ws", "repo", "Processor.php", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"),
                eq("Processor.run"), target.capture(), eq("CALLS"), eq("Processor.php"), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("doSomething")),
                "Expected CALLS edge to doSomething, got: " + target.getAllValues());
    }

    @Test
    void emitsImportsEdge() {
        Assumptions.assumeTrue(treeSitterAvailable, "Tree-sitter native library not available");

        String source = """
                <?php
                use App\\Services\\PaymentService;
                class Checkout {}
                """;

        CodeGraphStore store = mock(CodeGraphStore.class);
        PhpDriver driver = new PhpDriver();
        driver.index(parsePhp(source), source, "ws", "repo", "Checkout.php", store);

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).upsertEdge(eq("ws"), eq("repo"), any(),
                target.capture(), eq("IMPORTS"), any(), any());
        assertTrue(target.getAllValues().stream().anyMatch(v -> v.contains("PaymentService")),
                "Expected IMPORTS edge for PaymentService, got: " + target.getAllValues());
    }
}
