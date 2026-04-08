package com.eneve.agent.agent.handlers;

import com.eneve.agent.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JiraStatusMapperTest {

    private SettingsService settings;

    @BeforeEach
    void setUp() {
        settings = Mockito.mock(SettingsService.class);
        // Default mappings
        when(settings.get("roadmap.jira.status-map.closed",    "Done,Closed,Resolved")).thenReturn("Done,Closed,Resolved");
        when(settings.get("roadmap.jira.status-map.qa",        "In Review,QA,Testing")).thenReturn("In Review,QA,Testing");
        when(settings.get("roadmap.jira.status-map.in-progress","In Progress")).thenReturn("In Progress");
        when(settings.get("roadmap.jira.status-map.new",       "To Do,Open,New")).thenReturn("To Do,Open,New");
    }

    @Test
    void mapsNullToNull() {
        assertNull(JiraStatusMapper.map(null, settings));
    }

    @Test
    void mapsBlankToNull() {
        assertNull(JiraStatusMapper.map("   ", settings));
    }

    @Test
    void mapsDoneToClosedCaseInsensitive() {
        assertEquals("Closed", JiraStatusMapper.map("Done", settings));
        assertEquals("Closed", JiraStatusMapper.map("done", settings));
        assertEquals("Closed", JiraStatusMapper.map("DONE", settings));
    }

    @Test
    void mapsClosedToClosed() {
        assertEquals("Closed", JiraStatusMapper.map("Closed", settings));
        assertEquals("Closed", JiraStatusMapper.map("Resolved", settings));
    }

    @Test
    void mapsQAStatuses() {
        assertEquals("QA", JiraStatusMapper.map("QA", settings));
        assertEquals("QA", JiraStatusMapper.map("qa", settings));
        assertEquals("QA", JiraStatusMapper.map("In Review", settings));
        assertEquals("QA", JiraStatusMapper.map("Testing", settings));
    }

    @Test
    void mapsInProgress() {
        assertEquals("In Progress", JiraStatusMapper.map("In Progress", settings));
        assertEquals("In Progress", JiraStatusMapper.map("in progress", settings));
    }

    @Test
    void mapsNewStatuses() {
        assertEquals("New", JiraStatusMapper.map("To Do", settings));
        assertEquals("New", JiraStatusMapper.map("Open", settings));
        assertEquals("New", JiraStatusMapper.map("New", settings));
    }

    @Test
    void returnsRawStatusWhenNoMatchFound() {
        String unknown = "Waiting for Customer";
        assertEquals(unknown, JiraStatusMapper.map(unknown, settings));
    }

    @Test
    void closedTakesPriorityOverOtherBuckets() {
        // "Done" is in closed — must not bleed into other buckets
        assertEquals("Closed", JiraStatusMapper.map("Done", settings));
    }

    @Test
    void customMappingFromSettings() {
        when(settings.get("roadmap.jira.status-map.closed", "Done,Closed,Resolved"))
                .thenReturn("Finished,Complete");
        assertEquals("Closed", JiraStatusMapper.map("Finished", settings));
        assertEquals("Closed", JiraStatusMapper.map("Complete", settings));
        // "Done" is no longer closed under custom mapping
        assertNotEquals("Closed", JiraStatusMapper.map("Done", settings));
    }
}
