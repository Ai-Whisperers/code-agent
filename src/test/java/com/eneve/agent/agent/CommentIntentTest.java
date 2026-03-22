package com.eneve.agent.agent;

import com.eneve.agent.agent.model.CommentIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommentIntentTest {

    @Test
    void enumHasExpectedValues() {
        CommentIntent[] values = CommentIntent.values();
        
        assertEquals(2, values.length);
        assertEquals(CommentIntent.FIX, values[0]);
        assertEquals(CommentIntent.DISCUSS, values[1]);
    }

    @Test
    void enumValuesHaveCorrectNames() {
        assertEquals("FIX", CommentIntent.FIX.name());
        assertEquals("DISCUSS", CommentIntent.DISCUSS.name());
    }

    @Test
    void enumValueOfWorks() {
        assertEquals(CommentIntent.FIX, CommentIntent.valueOf("FIX"));
        assertEquals(CommentIntent.DISCUSS, CommentIntent.valueOf("DISCUSS"));
    }

    @Test
    void enumValueOfThrowsExceptionForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> CommentIntent.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> CommentIntent.valueOf("fix")); // case sensitive
        assertThrows(IllegalArgumentException.class, () -> CommentIntent.valueOf(""));
    }

    @Test
    void enumValueOfThrowsExceptionForNull() {
        assertThrows(NullPointerException.class, () -> CommentIntent.valueOf(null));
    }

    @Test
    void enumEquality() {
        assertEquals(CommentIntent.FIX, CommentIntent.FIX);
        assertEquals(CommentIntent.DISCUSS, CommentIntent.DISCUSS);
        assertNotEquals(CommentIntent.FIX, CommentIntent.DISCUSS);
        assertNotEquals(CommentIntent.DISCUSS, CommentIntent.FIX);
    }

    @Test
    void enumToString() {
        assertEquals("FIX", CommentIntent.FIX.toString());
        assertEquals("DISCUSS", CommentIntent.DISCUSS.toString());
    }

    @Test
    void enumOrdinal() {
        assertEquals(0, CommentIntent.FIX.ordinal());
        assertEquals(1, CommentIntent.DISCUSS.ordinal());
    }
}