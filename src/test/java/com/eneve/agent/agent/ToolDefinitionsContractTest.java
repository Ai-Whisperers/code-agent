package com.eneve.agent.agent;

import com.anthropic.models.messages.ToolUnion;
import com.eneve.agent.tools.ToolRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract test: every tool name in a {@link ToolDefinitions} mode must have a
 * corresponding {@link com.eneve.agent.tools.ToolExecutor} registered in the
 * {@link ToolRegistry}.
 *
 * <p>This catches the class of bug where a schema is defined in a mode but the
 * CDI bean is missing or misnamed, causing a silent "Unknown tool" error at runtime.
 */
@QuarkusTest
class ToolDefinitionsContractTest {

    @Inject
    ToolRegistry toolRegistry;

    static Stream<Arguments> modes() {
        return Stream.of(
                Arguments.of("all",            ToolDefinitions.all()),
                Arguments.of("readOnly",        ToolDefinitions.readOnly()),
                Arguments.of("docsGeneration",  ToolDefinitions.docsGeneration()),
                Arguments.of("planExecution",   ToolDefinitions.planExecution()),
                Arguments.of("chat",            ToolDefinitions.chat(false, false)),
                Arguments.of("chatAdmin",       ToolDefinitions.chat(true, true))
        );
    }

    @ParameterizedTest(name = "mode={0}: all schema names have a registered executor")
    @MethodSource("modes")
    void allSchemaNamesHaveRegisteredExecutors(String modeName, List<ToolUnion> tools) {
        Set<String> registeredExecutors = Set.copyOf(toolRegistry.toolNames());

        List<String> missing = tools.stream()
                .filter(ToolUnion::isTool)
                .map(tu -> tu.asTool().name())
                .filter(name -> !registeredExecutors.contains(name))
                .collect(Collectors.toList());

        if (!missing.isEmpty()) {
            fail("Mode '" + modeName + "' defines tool schema(s) with no registered ToolExecutor bean: "
                    + missing + ". Registered executors: " + registeredExecutors);
        }
    }

    @ParameterizedTest(name = "mode={0}: no duplicate tool names")
    @MethodSource("modes")
    void noDuplicateNamesInMode(String modeName, List<ToolUnion> tools) {
        List<String> allNames = tools.stream()
                .filter(ToolUnion::isTool)
                .map(tu -> tu.asTool().name())
                .collect(Collectors.toList());

        Set<String> unique = Set.copyOf(allNames);
        if (allNames.size() != unique.size()) {
            List<String> duplicates = allNames.stream()
                    .filter(n -> allNames.stream().filter(n::equals).count() > 1)
                    .distinct()
                    .collect(Collectors.toList());
            fail("Mode '" + modeName + "' contains duplicate tool names: " + duplicates);
        }
    }
}
