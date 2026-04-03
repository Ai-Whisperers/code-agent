package com.eneve.agent.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Factory for creating XXE-hardened XML {@link DocumentBuilder} instances.
 *
 * <p>Two hardening levels are provided — choose based on the document source:
 *
 * <table border="1">
 *   <caption>Hardening level by caller</caption>
 *   <tr><th>Method</th><th>Use when</th><th>Known callers</th></tr>
 *   <tr>
 *     <td>{@link #createSecureBuilder()}</td>
 *     <td>Document may contain a DOCTYPE / external DTD reference (e.g. JaCoCo XML reports,
 *         pom.xml, .NET project files). Suppresses all external entity resolution.</td>
 *     <td>{@code CoverageReporter}, .NET/MSBuild parsers</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #createStrictBuilder()}</td>
 *     <td>Document is tool-generated and must never contain a DOCTYPE at all
 *         (e.g. PMD, SpotBugs XML output). Throws if a DOCTYPE is present.</td>
 *     <td>PMD/SpotBugs report parsers</td>
 *   </tr>
 * </table>
 */
public final class XmlParserFactory {

    // SAX / Xerces feature URIs kept as constants for readability and single-point updates.
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String FEATURE_DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";

    private XmlParserFactory() {}

    /**
     * Creates a {@link DocumentBuilder} with full XXE hardening.
     * External DTDs, external entities, and XInclude are all disabled.
     * An entity resolver that suppresses all external lookups is pre-installed.
     */
    public static DocumentBuilder createSecureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
        return builder;
    }

    /**
     * Creates a {@link DocumentBuilder} that disallows DOCTYPE declarations entirely.
     * Use for tool-generated reports that are never expected to contain a DOCTYPE.
     */
    public static DocumentBuilder createStrictBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
        return factory.newDocumentBuilder();
    }
}
