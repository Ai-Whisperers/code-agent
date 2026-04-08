package com.eneve.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepoCoordinatesTest {

    @Test
    void parseBitbucketHttpsUrl() {
        String url = "https://bitbucket.org/myorg/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseBitbucketHttpsUrlWithGitExtension() {
        String url = "https://bitbucket.org/myorg/myrepo.git";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseBitbucketHttpsUrlWithTrailingSlash() {
        String url = "https://bitbucket.org/myorg/myrepo/";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseBitbucketHttpsUrlWithAuth() {
        String url = "https://user:pass@bitbucket.org/myorg/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseBitbucketSshUrl() {
        String url = "git@bitbucket.org:myorg/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseBitbucketSshUrlWithGitExtension() {
        String url = "git@bitbucket.org:myorg/myrepo.git";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsHttpsUrl() {
        String url = "https://dev.azure.com/myorg/myproject/_git/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsHttpsUrlWithAuth() {
        String url = "https://user@dev.azure.com/myorg/myproject/_git/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsHttpsUrlWithGitExtension() {
        String url = "https://dev.azure.com/myorg/myproject/_git/myrepo.git";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsSshUrl() {
        String url = "git@ssh.dev.azure.com:v3/myorg/myproject/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsSshUrlWithGitExtension() {
        String url = "git@ssh.dev.azure.com:v3/myorg/myproject/myrepo.git";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsLegacyUrl() {
        String url = "https://myorg.visualstudio.com/myproject/_git/myrepo";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseAzureDevOpsLegacyUrlWithGitExtension() {
        String url = "https://myorg.visualstudio.com/myproject/_git/myrepo.git";
        RepoCoordinates coords = RepoCoordinates.parse(url);
        
        assertEquals("myorg", coords.organization());
        assertEquals("myproject", coords.project());
        assertEquals("myrepo", coords.repository());
    }

    @Test
    void parseThrowsExceptionForInvalidUrl() {
        String invalidUrl = "https://unknown-host.com/myorg/myrepo";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> RepoCoordinates.parse(invalidUrl));
        
        assertTrue(exception.getMessage().contains("Cannot parse repository URL"));
        assertTrue(exception.getMessage().contains(invalidUrl));
    }

    @Test
    void parseThrowsExceptionForMalformedUrl() {
        String malformedUrl = "not-a-url";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> RepoCoordinates.parse(malformedUrl));
        
        assertTrue(exception.getMessage().contains("Cannot parse repository URL"));
    }

    @Test
    void httpsCloneUrlForBitbucket() {
        RepoCoordinates coords = new RepoCoordinates("myorg", "", "myrepo", RepoCoordinates.Platform.BITBUCKET);
        String cloneUrl = coords.httpsCloneUrl("user", "pass");
        
        assertEquals("https://user:pass@bitbucket.org/myorg/myrepo.git", cloneUrl);
    }

    @Test
    void httpsCloneUrlForBitbucketWithNullProject() {
        RepoCoordinates coords = new RepoCoordinates("myorg", null, "myrepo", RepoCoordinates.Platform.BITBUCKET);
        String cloneUrl = coords.httpsCloneUrl("user", "pass");
        
        assertEquals("https://user:pass@bitbucket.org/myorg/myrepo.git", cloneUrl);
    }

    @Test
    void httpsCloneUrlForAzureDevOps() {
        RepoCoordinates coords = new RepoCoordinates("myorg", "myproject", "myrepo", RepoCoordinates.Platform.AZURE_DEVOPS);
        String cloneUrl = coords.httpsCloneUrl("user", "pass");
        
        assertEquals("https://user:pass@dev.azure.com/myorg/myproject/_git/myrepo", cloneUrl);
    }

    @Test
    void repoWebUrlForBitbucket() {
        RepoCoordinates coords = new RepoCoordinates("myorg", "", "myrepo", RepoCoordinates.Platform.BITBUCKET);
        String webUrl = coords.repoWebUrl();
        
        assertEquals("https://bitbucket.org/myorg/myrepo.git", webUrl);
    }

    @Test
    void repoWebUrlForBitbucketWithNullProject() {
        RepoCoordinates coords = new RepoCoordinates("myorg", null, "myrepo", RepoCoordinates.Platform.BITBUCKET);
        String webUrl = coords.repoWebUrl();
        
        assertEquals("https://bitbucket.org/myorg/myrepo.git", webUrl);
    }

    @Test
    void repoWebUrlForAzureDevOps() {
        RepoCoordinates coords = new RepoCoordinates("myorg", "myproject", "myrepo", RepoCoordinates.Platform.AZURE_DEVOPS);
        String webUrl = coords.repoWebUrl();
        
        assertEquals("https://dev.azure.com/myorg/myproject/_git/myrepo", webUrl);
    }

    @Test
    void recordEquality() {
        RepoCoordinates coords1 = new RepoCoordinates("org", "proj", "repo", RepoCoordinates.Platform.AZURE_DEVOPS);
        RepoCoordinates coords2 = new RepoCoordinates("org", "proj", "repo", RepoCoordinates.Platform.AZURE_DEVOPS);
        RepoCoordinates coords3 = new RepoCoordinates("org", "proj", "different", RepoCoordinates.Platform.AZURE_DEVOPS);
        
        assertEquals(coords1, coords2);
        assertNotEquals(coords1, coords3);
        assertEquals(coords1.hashCode(), coords2.hashCode());
    }

    @Test
    void recordToString() {
        RepoCoordinates coords = new RepoCoordinates("myorg", "myproject", "myrepo", RepoCoordinates.Platform.AZURE_DEVOPS);
        String toString = coords.toString();
        
        assertTrue(toString.contains("myorg"));
        assertTrue(toString.contains("myproject"));
        assertTrue(toString.contains("myrepo"));
    }
}