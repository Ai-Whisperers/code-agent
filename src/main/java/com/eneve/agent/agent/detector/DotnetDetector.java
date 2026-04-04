package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import com.eneve.agent.util.XmlParserFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects .NET SDK projects from {@code *.csproj}, {@code *.fsproj}, {@code *.vbproj},
 * or {@code *.sln} files, reading the target framework from the project file or
 * {@code global.json}.
 */
public class DotnetDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(DotnetDetector.class);

    private static final Set<String> PROJECT_EXTENSIONS = Set.of(".csproj", ".fsproj", ".vbproj");

    private final ObjectMapper objectMapper;

    public DotnetDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        try (Stream<Path> stream = Files.list(projectRoot)) {
            List<Path> projectFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return PROJECT_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .sorted()
                    .toList();

            for (Path proj : projectFiles) {
                String version = readTargetFramework(proj);
                if (version != null) {
                    LOG.debugf("Detected .NET via %s: %s", proj.getFileName(), version);
                    return new ArchetypeInfo("dotnet", version);
                }
            }

            boolean hasSln;
            try (Stream<Path> slnStream = Files.list(projectRoot)) {
                hasSln = slnStream.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".sln"));
            }
            if (!hasSln && projectFiles.isEmpty()) return null;

        } catch (IOException e) {
            LOG.debugf("DotnetDetector: cannot list root dir: %s", e.getMessage());
            return null;
        }

        Path globalJson = projectRoot.resolve("global.json");
        if (Files.exists(globalJson)) {
            try {
                JsonNode root = objectMapper.readTree(globalJson.toFile());
                JsonNode sdk = root.get("sdk");
                if (sdk != null) {
                    JsonNode sdkVersion = sdk.get("version");
                    if (sdkVersion != null && !sdkVersion.asText().isBlank()) {
                        String v = sdkVersion.asText().trim();
                        LOG.debugf("Detected .NET via global.json sdk.version: %s", v);
                        return new ArchetypeInfo("dotnet", v);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("DotnetDetector: failed to parse global.json: %s", e.getMessage());
            }
        }

        LOG.debugf("Detected .NET project (no target framework found)");
        return new ArchetypeInfo("dotnet", "unknown");
    }

    private String readTargetFramework(Path projectFile) {
        try {
            DocumentBuilder builder = XmlParserFactory.createSecureBuilder();
            builder.setErrorHandler(null);
            try (InputStream in = Files.newInputStream(projectFile)) {
                Document doc = builder.parse(in);
                doc.getDocumentElement().normalize();

                NodeList tf = doc.getElementsByTagName("TargetFramework");
                if (tf.getLength() > 0) {
                    String text = tf.item(0).getTextContent().trim();
                    return text.isEmpty() ? null : text;
                }

                NodeList tfs = doc.getElementsByTagName("TargetFrameworks");
                if (tfs.getLength() > 0) {
                    String text = tfs.item(0).getTextContent().trim();
                    if (!text.isEmpty()) return text.split(";")[0].trim();
                }
            }
        } catch (Exception e) {
            LOG.debugf("DotnetDetector: cannot read %s: %s", projectFile.getFileName(), e.getMessage());
        }
        return null;
    }
}
