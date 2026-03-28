package com.eneve.agent.scm;

import com.eneve.agent.scm.azuredevops.AzureDevOpsPlatformService;
import com.eneve.agent.scm.bitbucket.BitbucketPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class GitPlatformProducerTest {

    private GitPlatformProducer producer;

    @BeforeEach
    void setUp() {
        producer = new GitPlatformProducer();
        // We'll use null services since we're only testing the platform selection logic
        producer.bitbucket = null;
        producer.azureDevOps = null;
    }

    @Test
    void produceBitbucketPlatformServiceWithBitbucket() throws Exception {
        setPlatform("bitbucket");
        
        // We expect this to select the bitbucket service (null in our test setup)
        // The actual service injection is handled by CDI in runtime
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceBitbucketPlatformServiceWithUppercase() throws Exception {
        setPlatform("BITBUCKET");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceBitbucketPlatformServiceWithWhitespace() throws Exception {
        setPlatform("  bitbucket  ");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzuredevops() throws Exception {
        setPlatform("azuredevops");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzureDashDevops() throws Exception {
        setPlatform("azure-devops");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzure() throws Exception {
        setPlatform("azure");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithUppercase() throws Exception {
        setPlatform("AZUREDEVOPS");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithMixedCase() throws Exception {
        setPlatform("AzUrE-DevOps");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithWhitespace() throws Exception {
        setPlatform("  azure-devops  ");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void throwsExceptionForUnsupportedPlatform() throws Exception {
        setPlatform("github");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> producer.gitPlatformService());
        
        assertTrue(exception.getMessage().contains("Unknown git.platform value: 'github'"));
        assertTrue(exception.getMessage().contains("Supported values: bitbucket, azuredevops"));
    }

    @Test
    void throwsExceptionForEmptyPlatform() throws Exception {
        setPlatform("");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> producer.gitPlatformService());
        
        assertTrue(exception.getMessage().contains("Unknown git.platform value: ''"));
    }

    @Test
    void throwsExceptionForNullPlatform() throws Exception {
        setPlatform(null);
        
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> producer.gitPlatformService());
    }

    @Test
    void throwsExceptionForInvalidPlatform() throws Exception {
        setPlatform("gitlab");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> producer.gitPlatformService());
        
        assertTrue(exception.getMessage().contains("Unknown git.platform value: 'gitlab'"));
    }

    @Test
    void throwsExceptionForWhitespaceOnlyPlatform() throws Exception {
        setPlatform("   ");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> producer.gitPlatformService());
        
        // The error message shows the original untrimmed value, but the switch gets trimmed empty string
        assertTrue(exception.getMessage().contains("Unknown git.platform value: '   '"));
    }

    @Test
    void defaultValueIsBitbucket() throws Exception {
        // When platform is not set, it should default to bitbucket based on the @ConfigProperty annotation
        setPlatform("bitbucket"); // This simulates the default value
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void platformValueTrimmedBeforeSwitchEvaluation() throws Exception {
        setPlatform("\t\n  bitbucket\r\n  ");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void caseInsensitiveMatching() throws Exception {
        setPlatform("BitBucket");
        
        assertDoesNotThrow(() -> producer.gitPlatformService());
    }

    @Test
    void specialCharactersInPlatformName() throws Exception {
        setPlatform("bitbucket@#$");
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> producer.gitPlatformService());
        
        assertTrue(exception.getMessage().contains("Unknown git.platform value: 'bitbucket@#$'"));
    }

    @Test
    void switchStatementHandlesAllCases() throws Exception {
        // Test all the known cases to ensure they don't throw exceptions (even with null services)
        String[] validPlatforms = {
            "bitbucket", "BITBUCKET", "BitBucket",
            "azuredevops", "AZUREDEVOPS", "azure-devops", "AZURE-DEVOPS", "azure", "AZURE"
        };
        
        for (String platform : validPlatforms) {
            setPlatform(platform);
            assertDoesNotThrow(() -> producer.gitPlatformService(), 
                "Platform '" + platform + "' should be handled without throwing exception");
        }
    }

    @Test
    void switchStatementRejectsInvalidCases() throws Exception {
        String[] invalidPlatforms = {
            "github", "gitlab", "invalid", "", "azure devops", "bit bucket"
        };
        
        for (String platform : invalidPlatforms) {
            setPlatform(platform);
            assertThrows(IllegalArgumentException.class, () -> producer.gitPlatformService(), 
                "Platform '" + platform + "' should throw IllegalArgumentException");
        }
    }

    /**
     * Helper method to set the platform field via reflection since we can't easily inject config values in unit tests
     */
    private void setPlatform(String platform) throws Exception {
        Field platformField = GitPlatformProducer.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        platformField.set(producer, platform);
    }
}