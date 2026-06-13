package com.voiceshopping.business.perspective;

import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PerspectiveHubService}.
 * <p>
 * Scope is restricted to logic that does NOT exercise the real
 * {@link io.agentscope.core.pipeline.MsgHub}: empty-input fast return and
 * catch-block degradation. {@code MsgHub} is a final class deeply coupled to
 * {@link io.agentscope.core.ReActAgent} internals — exercising it requires real
 * agent participants. Auto-broadcast and three-role flow are covered by the
 * {@code @Disabled} integration test {@code PerspectiveHubBroadcastIT} which
 * runs against real DashScope endpoints.
 */
class PerspectiveHubServiceTest {

    private AgentFactory agentFactory;
    private PerspectiveHubService service;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgentFactory.class);
        service = new PerspectiveHubService(agentFactory);
    }

    private RecommendedItem item(long id, String name, double price, String reason) {
        return new RecommendedItem(id, name, BigDecimal.valueOf(price), reason, 0.9, Map.of());
    }

    @Test
    @DisplayName("Empty items: returns \"\" and never builds a team")
    void emptyItems_shortCircuits() {
        assertEquals("", service.discuss("s1", "买跑鞋", List.of()));
        assertEquals("", service.discuss("s1", "买跑鞋", null));
        verify(agentFactory, never()).newPerspectiveTeam();
    }

    @Test
    @DisplayName("AgentFactory throws: discuss degrades to \"\", does not propagate")
    void factoryThrows_degradesToEmpty() {
        when(agentFactory.newPerspectiveTeam())
                .thenThrow(new RuntimeException("factory unavailable"));

        String result = service.discuss("s1", "买跑鞋",
                List.of(item(1, "A", 499.0, "x")));

        assertEquals("", result);
    }
}
