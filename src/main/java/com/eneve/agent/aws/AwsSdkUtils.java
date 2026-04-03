package com.eneve.agent.aws;

import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;

/**
 * Stateless utility methods for working with AWS SDK v2 data types.
 */
public final class AwsSdkUtils {

    private AwsSdkUtils() {}

    /**
     * Returns the value of the first tag matching {@code key}, or an empty string if absent.
     */
    public static String tagValue(List<Tag> tags, String key) {
        if (tags == null) return "";
        return tags.stream().filter(t -> key.equals(t.key()))
                .map(Tag::value).findFirst().orElse("");
    }

    /**
     * Extracts the trailing segment after the last {@code /} from an ARN.
     * Returns {@code "unknown"} for null input.
     */
    public static String arnToName(String arn) {
        if (arn == null) return "unknown";
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }
}
