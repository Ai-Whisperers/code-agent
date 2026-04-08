package com.eneve.agent.agent;

import com.eneve.agent.agent.model.QualityReport.TestPresenceSection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestPresenceCheckerPhpTest {

    private final TestPresenceChecker checker = new TestPresenceChecker();

    @TempDir
    Path tempDir;

    @Test
    void detectsPhpLanguage() throws IOException {
        Files.writeString(tempDir.resolve("Foo.php"), "<?php class Foo {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertTrue(result.detectedLanguages().contains("PHP"), "Expected PHP to be detected");
    }

    @Test
    void classifiesPhpUnitTestFileAsTest() throws IOException {
        Path testsDir = tempDir.resolve("tests");
        Files.createDirectories(testsDir);
        Files.writeString(testsDir.resolve("FooTest.php"), "<?php class FooTest extends TestCase {}");
        Files.writeString(tempDir.resolve("Foo.php"), "<?php class Foo {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertEquals(1, result.sourceFiles(), "Expected 1 source file");
        assertEquals(1, result.testFiles(), "Expected 1 test file");
        assertTrue(result.testRatio() > 0.0, "Ratio should be positive");
    }

    @Test
    void classifiesPhpFileWithTestInNameAsTest() throws IOException {
        Files.writeString(tempDir.resolve("UserServiceTest.php"), "<?php class UserServiceTest {}");
        Files.writeString(tempDir.resolve("UserService.php"), "<?php class UserService {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertEquals(1, result.sourceFiles());
        assertEquals(1, result.testFiles());
    }

    @Test
    void classifiesPhpFileInTestDirectoryAsTest() throws IOException {
        Path testDir = tempDir.resolve("test");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.php"), "<?php class FooTest {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertEquals(1, result.testFiles());
        assertEquals(0, result.sourceFiles());
    }

    @Test
    void regularPhpFileIsClassifiedAsSource() throws IOException {
        Files.writeString(tempDir.resolve("UserRepository.php"), "<?php class UserRepository {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertEquals(1, result.sourceFiles());
        assertEquals(0, result.testFiles());
    }

    @Test
    void phpRatioIsCorrectWithMultipleFiles() throws IOException {
        // 2 source + 2 tests = ratio 1.0
        Files.writeString(tempDir.resolve("UserService.php"), "<?php class UserService {}");
        Files.writeString(tempDir.resolve("OrderService.php"), "<?php class OrderService {}");
        Path tests = tempDir.resolve("tests");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("UserServiceTest.php"), "<?php class UserServiceTest {}");
        Files.writeString(tests.resolve("OrderServiceTest.php"), "<?php class OrderServiceTest {}");

        TestPresenceSection result = checker.check(tempDir, "ws", "repo");

        assertEquals(2, result.sourceFiles());
        assertEquals(2, result.testFiles());
        assertEquals(1.0, result.testRatio(), 0.01);
    }
}
