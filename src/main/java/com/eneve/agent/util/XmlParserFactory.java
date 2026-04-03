package com.eneve.agent.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Factory for creating XXE-hardened XML {@link DocumentBuilder} instances.
 *
 * <p>Two hardening levels are provided:
 * <ul>
 *   <li>{@link #createSecureBuilder()} — full XXE hardening suitable for parsing
 *       untrusted or externally-sourced XML (pom.xml, JaCoCo reports, .NET project files).</li>
 *   <li>{@link #createStrictBuilder()} — minimal hardening that disallows DOCTYPE
 *       declarations entirely, suitable for tool-generated reports (PMD, SpotBugs).</li>
 * </ul>
 */
public final class XmlParserFactory {

    private XmlParserFactory() {}

    /**
     * Creates a {@link DocumentBuilder} with full XXE hardening.
     * External DTDs, external entities, and XInclude are all disabled.
     */
    public static DocumentBuilder createSecureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
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
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder();
    }
}
