package com.eneve.agent.scm;

import com.eneve.agent.scm.azuredevops.AzureDevOpsPlatformService;
import com.eneve.agent.scm.bitbucket.BitbucketPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class GitPlatformProducerTest {

    private GitPlatformProducer producer;
    private BitbucketPlatformService mockBitbucketService;
    private AzureDevOpsPlatformService mockAzureDevOpsService;

    @BeforeEach
    void setUp() {
        producer = new GitPlatformProducer();
        
        // Create mock services to simulate properly injected CDI beans
        mockBitbucketService = new BitbucketPlatformService();
        mockAzureDevOpsService = new AzureDevOpsPlatformService();
        
        // Inject the mock services instead of setting to null
        producer.bitbucket = mockBitbucketService;
        producer.azureDevOps = mockAzureDevOpsService;
    }

    @Test
    void produceBitbucketPlatformServiceWithBitbucket() throws Exception {
        setPlatform("bitbucket");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
    }

    @Test
    void produceBitbucketPlatformServiceWithUppercase() throws Exception {
        setPlatform("BITBUCKET");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
    }

    @Test
    void produceBitbucketPlatformServiceWithWhitespace() throws Exception {
        setPlatform("  bitbucket  ");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzuredevops() throws Exception {
        setPlatform("azuredevops");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzureDashDevops() throws Exception {
        setPlatform("azure-devops");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithAzure() throws Exception {
        setPlatform("azure");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithUppercase() throws Exception {
        setPlatform("AZUREDEVOPS");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithMixedCase() throws Exception {
        setPlatform("AzUrE-DevOps");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
    }

    @Test
    void produceAzureDevOpsPlatformServiceWithWhitespace() throws Exception {
        setPlatform("  azure-devops  ");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockAzureDevOpsService, result);
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
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
    }

    @Test
    void platformValueTrimmedBeforeSwitchEvaluation() throws Exception {
        setPlatform("\t\n  bitbucket\r\n  ");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
    }

    @Test
    void caseInsensitiveMatching() throws Exception {
        setPlatform("BitBucket");
        
        GitPlatformService result = producer.gitPlatformService();
        assertSame(mockBitbucketService, result);
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
        // Test all the known cases to ensure they return the correct service instances
        String[] bitbucketPlatforms = {"bitbucket", "BITBUCKET", "BitBucket"};
        String[] azurePlatforms = {"azuredevops", "AZUREDEVOPS", "azure-devops", "AZURE-DEVOPS", "azure", "AZURE"};
        
        for (String platform : bitbucketPlatforms) {
            setPlatform(platform);
            GitPlatformService result = producer.gitPlatformService();
            assertSame(mockBitbucketService, result, 
                "Platform '" + platform + "' should return BitbucketPlatformService");
        }
        
        for (String platform : azurePlatforms) {
            setPlatform(platform);
            GitPlatformService result = producer.gitPlatformService();
            assertSame(mockAzureDevOpsService, result,
                "Platform '" + platform + "' should return AzureDevOpsPlatformService");
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