package com.voiceshopping.common.enums;

/**
 * 用户意图枚举 — 由意图理解 Agent 分类输出。
 * User intent classification from the intent understanding agent.
 */
public enum IntentEnum {

    /** 用户想搜索或购买某个商品 — User wants to search for or purchase a product. */
    PRODUCT_RECOMMENDATION,

    /** 信息不完整，需要追问澄清需求 — Insufficient information, need to ask clarifying questions. */
    CLARIFY_NEEDED,

    /** 用户想对比多个商品 — User wants to compare multiple products. */
    PRODUCT_COMPARE,

    /** 闲聊，与购物无关 — Casual conversation unrelated to shopping. */
    CHITCHAT,

    /** 用户确认下单 — User confirms an order. */
    ORDER_CONFIRM,

    /** 超出业务范围（政治、医疗建议等） — Out of business scope (politics, medical advice, etc.). */
    OUT_OF_SCOPE
}
