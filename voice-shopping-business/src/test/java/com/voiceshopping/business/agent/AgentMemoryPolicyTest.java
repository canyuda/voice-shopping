package com.voiceshopping.business.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentMemoryPolicy}: focuses on memory boundary handling.
 * Uses real {@link InMemoryMemory} backing a stub agent so deleteMessage / clear
 * semantics are exercised against the actual AgentScope implementation.
 */
class AgentMemoryPolicyTest {

    private final AgentMemoryPolicy policy = new AgentMemoryPolicy();

    // ---- before*Call hooks ----

    @Test
    void beforeIntentCall_clearsAllMessages() {
        ReActAgent agent = stubAgent(buildMemory(5));

        policy.beforeIntentCall(agent);

        assertThat(agent.getMemory().getMessages()).isEmpty();
    }

    @Test
    void beforeClarifyCall_clearsAllMessages() {
        ReActAgent agent = stubAgent(buildMemory(3));

        policy.beforeClarifyCall(agent);

        assertThat(agent.getMemory().getMessages()).isEmpty();
    }

    @Test
    void beforeRecommendCall_keepsLast8WhenOverLimit() {
        Memory memory = buildMemory(12);
        ReActAgent agent = stubAgent(memory);

        policy.beforeRecommendCall(agent);

        assertThat(memory.getMessages()).hasSize(8);
        // Oldest 4 dropped — first remaining message originally was index 4.
        assertThat(memory.getMessages().get(0).getTextContent()).isEqualTo("msg-4");
        assertThat(memory.getMessages().get(7).getTextContent()).isEqualTo("msg-11");
    }

    @Test
    void beforeRecommendCall_belowLimitIsNoOp() {
        Memory memory = buildMemory(5);
        ReActAgent agent = stubAgent(memory);

        policy.beforeRecommendCall(agent);

        assertThat(memory.getMessages()).hasSize(5);
    }

    @Test
    void beforeEmotionCall_keepsLast40WhenOverLimit() {
        Memory memory = buildMemory(60);
        ReActAgent agent = stubAgent(memory);

        policy.beforeEmotionCall(agent);

        assertThat(memory.getMessages()).hasSize(40);
        assertThat(memory.getMessages().get(0).getTextContent()).isEqualTo("msg-20");
    }

    @Test
    void beforeEmotionCall_exactlyAtLimitIsNoOp() {
        Memory memory = buildMemory(40);
        ReActAgent agent = stubAgent(memory);

        policy.beforeEmotionCall(agent);

        assertThat(memory.getMessages()).hasSize(40);
        // Untouched: first message must still be msg-0.
        assertThat(memory.getMessages().get(0).getTextContent()).isEqualTo("msg-0");
    }

    @Test
    void beforeEmotionCall_emptyMemoryIsNoOp() {
        Memory memory = new InMemoryMemory();
        ReActAgent agent = stubAgent(memory);

        policy.beforeEmotionCall(agent);

        assertThat(memory.getMessages()).isEmpty();
    }

    // ---- helpers ----

    private static ReActAgent stubAgent(Memory memory) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getMemory()).thenReturn(memory);
        return agent;
    }

    private static Memory buildMemory(int count) {
        InMemoryMemory memory = new InMemoryMemory();
        for (int i = 0; i < count; i++) {
            memory.addMessage(
                    Msg.builder().role(MsgRole.USER).textContent("msg-" + i).build());
        }
        return memory;
    }
}
