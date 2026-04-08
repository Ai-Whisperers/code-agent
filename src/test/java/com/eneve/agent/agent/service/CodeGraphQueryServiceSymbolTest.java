package com.eneve.agent.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGraphQueryServiceSymbolTest {

    @Test
    void methodLike_javaQualifiedSignature() {
        assertTrue(CodeGraphQueryService.isMethodLikeSymbol("com.foo.Bar.baz(java.lang.String)"));
        assertTrue(CodeGraphQueryService.isMethodLikeSymbol("com.foo.Bar#baz(int)"));
    }

    @Test
    void methodLike_legacySimplePair() {
        assertTrue(CodeGraphQueryService.isMethodLikeSymbol("StringHelper.sanitize"));
        assertTrue(CodeGraphQueryService.isMethodLikeSymbol("Foo.bar"));
    }

    @Test
    void typeLike_qualifiedJavaType() {
        assertFalse(CodeGraphQueryService.isMethodLikeSymbol("com.foo.Bar"));
        assertFalse(CodeGraphQueryService.isMethodLikeSymbol("Foo"));
    }
}
