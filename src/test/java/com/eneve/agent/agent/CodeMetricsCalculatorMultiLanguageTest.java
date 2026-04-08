package com.eneve.agent.agent;

import com.eneve.agent.agent.CodeMetricsCalculator.CodeMetricsSnapshot;
import com.eneve.agent.agent.CodeMetricsCalculator.MethodMetric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeMetricsCalculatorMultiLanguageTest {

    private final CodeMetricsCalculator calculator = new CodeMetricsCalculator();

    @TempDir
    Path tempDir;

    // ─── C# ─────────────────────────────────────────────────────────────────────

    @Test
    void csharpSimpleMethodHasBaseComplexityOfOne() throws IOException {
        Files.writeString(tempDir.resolve("Foo.cs"), """
                public class Foo {
                    public void Simple() {
                        Console.WriteLine("hello");
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertFalse(snap.methods().isEmpty(), "Expected at least one method");
        MethodMetric method = findMethod(snap.methods(), "Simple");
        assertNotNull(method, "Simple method not found");
        assertEquals(1, method.cyclomaticComplexity(), "Base CC should be 1");
    }

    @Test
    void csharpMethodWithBranchesHasCorrectComplexity() throws IOException {
        Files.writeString(tempDir.resolve("Bar.cs"), """
                public class Bar {
                    public int Calculate(int x) {
                        if (x > 0) {
                            for (int i = 0; i < x; i++) {
                                Console.WriteLine(i);
                            }
                        } else {
                            return -1;
                        }
                        return x;
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        MethodMetric method = findMethod(snap.methods(), "Calculate");
        assertNotNull(method, "Calculate method not found");
        // CC: 1 (base) + 1 (if) + 1 (for) = 3
        assertTrue(method.cyclomaticComplexity() >= 3, "Expected CC >= 3, got " + method.cyclomaticComplexity());
    }

    // ─── TypeScript ──────────────────────────────────────────────────────────────

    @Test
    void typescriptSimpleMethodHasBaseComplexityOfOne() throws IOException {
        Files.writeString(tempDir.resolve("service.ts"), """
                export class UserService {
                    getUser(id: number): string {
                        return 'user-' + id;
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertFalse(snap.methods().isEmpty(), "Expected at least one method");
        MethodMetric method = findMethod(snap.methods(), "getUser");
        assertNotNull(method, "getUser method not found");
        assertEquals(1, method.cyclomaticComplexity(), "Base CC should be 1");
    }

    @Test
    void typescriptMethodWithBranchesHasCorrectComplexity() throws IOException {
        Files.writeString(tempDir.resolve("calc.ts"), """
                export class Calculator {
                    compute(x: number, y: number): number {
                        if (x > 0 && y > 0) {
                            for (let i = 0; i < x; i++) {
                                console.log(i);
                            }
                        } else if (x < 0) {
                            return -1;
                        }
                        return x + y;
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        MethodMetric method = findMethod(snap.methods(), "compute");
        assertNotNull(method, "compute method not found");
        // CC: 1 (base) + 1 (if) + 1 (&&) + 1 (for) + 1 (else if) >= 4
        assertTrue(method.cyclomaticComplexity() >= 4,
                "Expected CC >= 4, got " + method.cyclomaticComplexity());
    }

    @Test
    void typescriptTopLevelFunctionIsAnalysed() throws IOException {
        Files.writeString(tempDir.resolve("utils.ts"), """
                export function processItems(items: string[]): string[] {
                    const result: string[] = [];
                    for (const item of items) {
                        if (item.length > 0) {
                            result.push(item.toUpperCase());
                        }
                    }
                    return result;
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertFalse(snap.methods().isEmpty(), "Expected at least one method/function");
    }

    // ─── PHP ─────────────────────────────────────────────────────────────────────

    @Test
    void phpSimpleMethodHasBaseComplexityOfOne() throws IOException {
        Files.writeString(tempDir.resolve("Foo.php"), """
                <?php
                class Foo {
                    public function simple(): string {
                        return 'hello';
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertFalse(snap.methods().isEmpty(), "Expected at least one method");
        MethodMetric method = findMethod(snap.methods(), "simple");
        assertNotNull(method, "simple method not found");
        assertEquals(1, method.cyclomaticComplexity(), "Base CC should be 1");
    }

    @Test
    void phpMethodWithBranchesHasCorrectComplexity() throws IOException {
        Files.writeString(tempDir.resolve("Calculator.php"), """
                <?php
                class Calculator {
                    public function compute(int $x, int $y): int {
                        if ($x > 0 && $y > 0) {
                            foreach (range(0, $x) as $i) {
                                echo $i;
                            }
                        } elseif ($x < 0) {
                            return -1;
                        }
                        return $x + $y;
                    }
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        MethodMetric method = findMethod(snap.methods(), "compute");
        assertNotNull(method, "compute method not found");
        // CC: 1 (base) + 1 (if) + 1 (&&) + 1 (foreach) + 1 (elseif) >= 4
        assertTrue(method.cyclomaticComplexity() >= 4,
                "Expected CC >= 4, got " + method.cyclomaticComplexity());
    }

    @Test
    void phpDeclarationFilesAreSkipped() throws IOException {
        Files.writeString(tempDir.resolve("stub.d.ts"), """
                declare module 'foo' {
                    export function bar(): void;
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertTrue(snap.methods().isEmpty(), "Declaration files should not be analysed");
    }

    // ─── Mixed project ────────────────────────────────────────────────────────────

    @Test
    void mixedProjectAnalysesAllSupportedLanguages() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), """
                public class App {
                    public void run() {}
                }
                """);
        Files.writeString(tempDir.resolve("Service.cs"), """
                public class Service {
                    public void Execute() {}
                }
                """);
        Files.writeString(tempDir.resolve("component.ts"), """
                export class AppComponent {
                    render(): string { return ''; }
                }
                """);
        Files.writeString(tempDir.resolve("Helper.php"), """
                <?php
                class Helper {
                    public function help(): void {}
                }
                """);

        CodeMetricsSnapshot snap = calculator.calculate(tempDir, "ws", "repo", "main", 10);
        assertTrue(snap.totalMethods() >= 4,
                "Expected at least 4 methods (one per language), got " + snap.totalMethods());
    }

    // ─── Helper ───────────────────────────────────────────────────────────────────

    private static MethodMetric findMethod(List<MethodMetric> methods, String name) {
        return methods.stream()
                .filter(m -> m.methodName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
