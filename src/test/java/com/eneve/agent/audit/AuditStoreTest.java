package com.eneve.agent.audit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class AuditStoreTest {

    @Test
    void auditStoreIsApplicationScoped() {
        assertTrue(AuditStore.class.isAnnotationPresent(
                jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test
    void auditStoreHasDataSourceField() throws NoSuchFieldException {
        Field field = AuditStore.class.getDeclaredField("dataSource");
        assertNotNull(field);
        assertTrue(field.isAnnotationPresent(jakarta.inject.Inject.class));
    }

    @Test
    void auditStoreHasSaveMethod() throws NoSuchMethodException {
        assertNotNull(AuditStore.class.getMethod("save", AuditEntry.class));
    }

    @Test
    void auditStoreHasSearchMethod() throws NoSuchMethodException {
        assertNotNull(AuditStore.class.getMethod(
                "search", String.class, String.class, String.class, int.class));
    }

    @Test
    void auditStoreHasFindByResourceIdMethod() throws NoSuchMethodException {
        assertNotNull(AuditStore.class.getMethod("findByResourceId", String.class, int.class));
    }
}
