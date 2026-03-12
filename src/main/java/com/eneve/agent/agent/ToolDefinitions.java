package com.eneve.agent.agent;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

/**
 * Defines the tool schemas exposed to Claude during the agentic loop.
 */
public final class ToolDefinitions {

    private ToolDefinitions() { }

    public static List<ToolUnion> all() {
        return List.of(
                ToolUnion.ofTool(readFile()),
                ToolUnion.ofTool(writeFile()),
                ToolUnion.ofTool(runCommand()),
                ToolUnion.ofTool(listFiles())
        );
    }

    public static List<ToolUnion> readOnly() {
        return List.of(
                ToolUnion.ofTool(readFile()),
                ToolUnion.ofTool(runCommand()),
                ToolUnion.ofTool(listFiles())
        );
    }

    private static Tool readFile() {
        return Tool.builder()
                .name("read_file")
                .description("Read the contents of a file from the repository. Returns the full text content.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the file from the repository root"
                                )))
                                .build())
                        .addRequired("path")
                        .build())
                .build();
    }

    private static Tool writeFile() {
        return Tool.builder()
                .name("write_file")
                .description("Write or overwrite a file in the repository. Creates parent directories if needed.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the file from the repository root"
                                )))
                                .putAdditionalProperty("content", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The full content to write to the file"
                                )))
                                .build())
                        .addRequired("path")
                        .addRequired("content")
                        .build())
                .build();
    }

    private static Tool runCommand() {
        return Tool.builder()
                .name("run_command")
                .description("Run a shell command in the repository root. Only allowed commands: mvn, gradle, git diff, git status, ls, find, cat. Returns exit code and output.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("command", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The shell command to execute"
                                )))
                                .build())
                        .addRequired("command")
                        .build())
                .build();
    }

    private static Tool listFiles() {
        return Tool.builder()
                .name("list_files")
                .description("List files and directories (up to 3 levels deep) in the given directory.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("directory", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Relative path to the directory (default: repository root)"
                                )))
                                .build())
                        .build())
                .build();
    }
}
