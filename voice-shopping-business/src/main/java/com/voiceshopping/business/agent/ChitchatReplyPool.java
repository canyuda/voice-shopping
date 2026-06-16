package com.voiceshopping.business.agent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 闲聊兜底文案池：CHITCHAT 分支不调用 EmotionAgent，从池内随机抽一句。
 * <p>
 * 文案设计原则：
 * <ul>
 *   <li>≤ 30 字，TTS 时长可控</li>
 *   <li>不带"亲"/"宝"等带货客服腔，不带"哈哈"等过度热情语气词</li>
 *   <li>都引导回购物场景（含"购物"/"商品"/"挑"/"买"等关键词）</li>
 *   <li>语气自然像朋友说话，不死板</li>
 * </ul>
 */
public final class ChitchatReplyPool {

    private static final List<String> REPLIES = List.of(
            "这话题我接不上，要不你说想看点啥？",
            "我更擅长帮你挑东西，要不试试？",
            "这个不太懂，咱聊点你想买的呗？",
            "嗯，你想找啥商品我可以帮你看看",
            "这事我搞不定，购物的话尽管问",
            "聊不来这个，要不告诉我你想买啥",
            "我帮你挑商品比较在行，说说需求？",
            "这个跳过吧，你想买点什么？",
            "我对这个没研究，购物的话可以聊",
            "这话题超纲了，要不看看商品？"
    );

    private ChitchatReplyPool() {
        throw new UnsupportedOperationException("ChitchatReplyPool is a static utility class");
    }

    /**
     * 从文案池随机抽取一条。
     */
    public static String randomReply() {
        return REPLIES.get(ThreadLocalRandom.current().nextInt(REPLIES.size()));
    }

    /**
     * 仅供测试用：返回不可变快照。
     */
    public static List<String> repliesSnapshot() {
        return REPLIES;
    }
}
