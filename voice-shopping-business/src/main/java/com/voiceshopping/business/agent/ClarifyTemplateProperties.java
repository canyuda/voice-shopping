package com.voiceshopping.business.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Clarify 单字段问句模板配置。
 * <p>
 * 单字段缺失场景下查表直接返回模板问句，跳过 ClarifyAgent LLM 调用。
 * 多字段缺失场景仍走 LLM（自然语言组合更灵活）。
 *
 * @param singleSlotTemplates key=slot 名（如 scenario/budget/gender/brand），
 *                            value=对应的中文模板问句
 */
@ConfigurationProperties(prefix = "voice-shopping.clarify")
public record ClarifyTemplateProperties(
        Map<String, String> singleSlotTemplates
) {
    public ClarifyTemplateProperties {
        if (singleSlotTemplates == null) {
            singleSlotTemplates = Map.of();
        }
    }
}
