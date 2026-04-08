package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Renders Mermaid diagram source to PNG using the local Mermaid CLI (mmdc).
 * This is a shared service used by both the Confluence page publisher and the
 * Bitbucket PR summary diagram upload flows.
 */
@ApplicationScoped
public class MermaidPngRenderer {

    private static final Logger LOG = Logger.getLogger(MermaidPngRenderer.class);
    private static final long MMDC_TIMEOUT_SECONDS = 60;

    /**
     * Renders a Mermaid diagram to PNG bytes using the local {@code mmdc} CLI.
     *
     * @param mermaidCode raw Mermaid diagram source (without fences)
     * @return PNG image bytes
     * @throws Exception if mmdc is not found, times out, or exits with an error
     */
    public byte[] renderToPng(String mermaidCode) throws Exception {
        Path tmpDir = Files.createTempDirectory("mermaid-render");
        Path inputFile = tmpDir.resolve("input.mmd");
        Path outputFile = tmpDir.resolve("output.png");
        try {
            Files.writeString(inputFile, mermaidCode.strip(), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "mmdc",
                    "-i", inputFile.toString(),
                    "-o", outputFile.toString(),
                    "-b", "white",
                    "--scale", "2"
            );
            String chromiumPath = System.getenv("PUPPETEER_EXECUTABLE_PATH");
            if (chromiumPath != null && !chromiumPath.isBlank()) {
                pb.environment().put("PUPPETEER_EXECUTABLE_PATH", chromiumPath);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(MMDC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("mmdc timed out after " + MMDC_TIMEOUT_SECONDS + "s");
            }

            if (process.exitValue() != 0) {
                throw new RuntimeException("mmdc failed (exit " + process.exitValue() + "): "
                        + processOutput.substring(0, Math.min(processOutput.length(), 500)));
            }

            if (!Files.exists(outputFile)) {
                throw new RuntimeException("mmdc did not produce output file. stdout: "
                        + processOutput.substring(0, Math.min(processOutput.length(), 500)));
            }

            byte[] png = Files.readAllBytes(outputFile);
            LOG.debugf("Rendered Mermaid diagram to PNG (%d bytes)", png.length);
            return png;
        } finally {
            deleteQuietly(inputFile);
            deleteQuietly(outputFile);
            deleteQuietly(tmpDir);
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
