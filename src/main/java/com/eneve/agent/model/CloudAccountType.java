package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Cloud provider type")
public enum CloudAccountType {
    AWS,
    AZURE,
    GOOGLE,
    OTHER
}
