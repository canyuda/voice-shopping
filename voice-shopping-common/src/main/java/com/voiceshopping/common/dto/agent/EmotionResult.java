package com.voiceshopping.common.dto.agent;

import java.util.List;

/**
 * Final spoken response produced by the emotion agent.
 * <p>
 * The two fields are deliberately separated:
 * <ul>
 *   <li>{@code speechText} — concise, natural spoken text fed to TTS.</li>
 *   <li>{@code displayBlocks} — the full recommended items for frontend
 *       product cards, carrying price/attributes that the voice channel omits.</li>
 * </ul>
 * Voice stays lean; visuals stay informative.
 *
 * @param speechText    spoken text for TTS synthesis
 * @param displayBlocks full recommended items for UI rendering
 */
public record EmotionResult(
        String speechText,
        List<RecommendedItem> displayBlocks
) {
}
