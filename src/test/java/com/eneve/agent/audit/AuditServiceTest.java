package com.eneve.agent.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService();
    }

    @Test
    void auditServiceIsRequestScoped() {
        assertTrue(AuditService.class.isAnnotationPresent(
                jakarta.enterprise.context.RequestScoped.class));
    }

    @Test
    void auditServiceHasAuditStoreField() throws NoSuchFieldException {
        Field field = AuditService.class.getDeclaredField("auditStore");
        assertNotNull(field);
        assertTrue(field.isAnnotationPresent(jakarta.inject.Inject.class));
    }

    @Test
    void auditServiceHasObjectMapperField() throws NoSuchFieldException {
        Field field = AuditService.class.getDeclaredField("mapper");
        assertNotNull(field);
        assertTrue(field.isAnnotationPresent(jakarta.inject.Inject.class));
    }

    @Test
    void auditServiceHasLogMethodWithDetail() throws NoSuchMethodException {
        assertNotNull(AuditService.class.getMethod(
                "log", String.class, String.class, String.class, String.class, Map.class));
    }

    @Test
    void auditServiceHasLogMethodWithoutDetail() throws NoSuchMethodException {
        assertNotNull(AuditService.class.getMethod(
                "log", String.class, String.class, String.class, String.class));
    }

    @Test
    void logMethodWithNullDetailDoesNotThrow() {
        // auditStore is null (no CDI), but the async virtual thread catches errors internally
        assertDoesNotThrow(() -> auditService.log("JOBS", "JOB_SUBMITTED", "job", "job-1", null));
    }

    @Test
    void logWithoutDetailDoesNotThrow() {
        assertDoesNotThrow(() -> auditService.log("SETTINGS", "SETTING_CHANGED", "setting", "key1"));
    }
}
