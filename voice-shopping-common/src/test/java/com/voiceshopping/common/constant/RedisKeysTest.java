package com.voiceshopping.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeysTest {

    @Test
    void scope_buildsExpectedKeyWithVsPrefix() {
        assertEquals("vs:scope:s1", RedisKeys.scope("s1"));
    }

    @Test
    void scope_acceptsArbitrarySessionIds() {
        assertEquals("vs:scope:550e8400-e29b-41d4-a716-446655440000",
                RedisKeys.scope("550e8400-e29b-41d4-a716-446655440000"));
    }
}
