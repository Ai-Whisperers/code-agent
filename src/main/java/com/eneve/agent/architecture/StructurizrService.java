package com.eneve.agent.architecture;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.dsl.StructurizrDslParserException;
import com.structurizr.export.Diagram;
import com.structurizr.export.mermaid.MermaidDiagramExporter;
import com.structurizr.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Validates Structurizr DSL content and exports each view to Mermaid diagram source.
 *
 * <p>This service is pure Java — no subprocess, no network call, no Structurizr server required.
 * The {@code structurizr-dsl} library (which transitively includes {@code structurizr-export})
 * runs entirely in-process.
 */
@ApplicationScoped
public class StructurizrService {

    private static final Logger LOG = Logger.getLogger(StructurizrService.class);

    /**
     * Parses and validates the given Structurizr DSL string, then exports every view to Mermaid.
     *
     * @param dslContent raw Structurizr DSL text
     * @return one {@link ArchitectureDiagramDto} per view defined in the workspace
     * @throws StructurizrDslParserException if the DSL is syntactically or semantically invalid
     * @throws IOException                   if a temporary file cannot be created for the parser
     */
    public List<ArchitectureDiagramDto> validateAndExport(String dslContent)
            throws StructurizrDslParserException, IOException {

        // StructurizrDslParser requires a File (it resolves !include directives relative to it).
        // We write to a temp file and delete it afterwards.
        Path tmpFile = Files.createTempFile("structurizr-", ".dsl");
        try {
            Files.writeString(tmpFile, dslContent, StandardCharsets.UTF_8);
            return parseAndExport(tmpFile.toFile());
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    /**
     * Convenience overload that reads DSL from a file on disk (e.g. {@code docs/architecture.dsl}
     * after the agent has written it to the workspace).
     */
    public List<ArchitectureDiagramDto> validateAndExportFile(Path dslFile)
            throws StructurizrDslParserException, IOException {
        return parseAndExport(dslFile.toFile());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private List<ArchitectureDiagramDto> parseAndExport(File dslFile)
            throws StructurizrDslParserException {

        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(dslFile);
        Workspace workspace = parser.getWorkspace();

        MermaidDiagramExporter exporter = new MermaidDiagramExporter();
        Collection<Diagram> diagrams = exporter.export(workspace);

        List<ArchitectureDiagramDto> result = new ArrayList<>(diagrams.size());
        for (Diagram diagram : diagrams) {
            View view = diagram.getView();
            String viewType = view.getClass().getSimpleName()
                    .replace("View", "");
            // Structurizr exports top-down (TD) by default; left-to-right (LR) is easier to read
            // for wide deployment and container diagrams.
            String mermaid = diagram.getDefinition()
                    .replaceFirst("(?m)^(\\s*graph\\s+)TD\\b", "$1LR");
            result.add(new ArchitectureDiagramDto(
                    view.getKey(),
                    viewType,
                    mermaid
            ));
            LOG.debugf("Exported view '%s' (%s) to Mermaid (%d chars)",
                    view.getKey(), viewType, diagram.getDefinition().length());
        }

        LOG.infof("StructurizrService: exported %d view(s) from DSL", result.size());
        return result;
    }
}
