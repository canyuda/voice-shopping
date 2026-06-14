package com.voiceshopping.business.rec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.business.agent.AgentMemoryPolicy;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates natural recommendation reasons for each product via LLM.
 * Falls back to empty reasons on any failure (LLM error, parse error).
 */
@Service
public class RecommendReasonService {

    private static final Logger log = LoggerFactory.getLogger(RecommendReasonService.class);

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;
    private final AgentMemoryPolicy memoryPolicy;

    public RecommendReasonService(AgentFactory agentFactory,
                                  ObjectMapper objectMapper,
                                  AgentMemoryPolicy memoryPolicy) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
        this.memoryPolicy = memoryPolicy;
    }

    /**
     * Attach LLM-generated recommendation reasons to each product.
     *
     * @param sessionId  session ID for agent cache lookup
     * @param userNeeds  combined user needs description
     * @param products   products to generate reasons for (typically top 3)
     * @return products with reasons filled in, or original products on failure
     */
    public List<RecommendedItem> attachReasons(String sessionId,
                                                String userNeeds,
                                                List<RecommendedItem> products) {
        if (products == null || products.isEmpty()) {
            return products;
        }

        try {
            ReActAgent agent = agentFactory.getRecAgent(sessionId);
            memoryPolicy.beforeRecommendCall(agent);
            String userMsg = buildUserMessage(userNeeds, products);

            Msg response = agent.call(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(userMsg)
                            .build()
            ).block();

            String responseText = response != null ? response.getTextContent() : null;
            if (responseText == null || responseText.isBlank()) {
                log.warn("RecAgent returned empty response for session={}", sessionId);
                return products;
            }
            return applyReasons(products, responseText);
        } catch (Exception e) {
            log.warn("Failed to generate recommendation reasons, returning without reasons: {}",
                    e.getMessage());
            return products;
        }
    }

    private String buildUserMessage(String userNeeds, List<RecommendedItem> products) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("userNeeds", userNeeds);

        List<Map<String, Object>> productMaps = products.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productId", p.productId());
                    m.put("name", p.name());
                    m.put("price", p.price());
                    m.put("attributes", p.attributes());
                    return m;
                })
                .toList();
        msg.put("products", productMaps);

        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize recommendation request", e);
        }
    }

    private List<RecommendedItem> applyReasons(List<RecommendedItem> products, String response) {
        try {
            List<Map<String, String>> reasons = objectMapper.readValue(
                    response, new TypeReference<>() {});

            // Build productId -> reason map
            Map<Long, String> reasonMap = reasons.stream()
                    .filter(r -> r.containsKey("productId") && r.containsKey("reason"))
                    .collect(Collectors.toMap(
                            r -> Long.parseLong(r.get("productId")),
                            r -> r.get("reason"),
                            (a, b) -> a));

            return products.stream()
                    .map(p -> {
                        String reason = reasonMap.getOrDefault(p.productId(), "");
                        return reason.isEmpty() ? p : p.withReason(reason);
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse LLM reason response: {}", e.getMessage());
            return products;
        }
    }
}
