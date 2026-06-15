package com.voiceshopping.business.order;

import com.voiceshopping.common.dto.agent.LastRecommendationsSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a user's spoken reference ("第二款" / "刚才那双" / "倒数第二个")
 * to a concrete {@code productId} from
 * {@link LastRecommendationsSnapshot#productIds()}.
 * <p>
 * Resolution priority (first match wins):
 * <ol>
 *   <li><b>Reverse markers</b> ("倒数" / "最后第") → last item, regardless
 *       of any ordinal that follows. Without this guard, "倒数第二款" would
 *       match the ordinal regex and return index 1, which is the wrong
 *       semantic (user wants the last item).</li>
 *   <li><b>Position keywords</b>:
 *     <ul>
 *       <li>"最后" / "末" → last item</li>
 *       <li>"第一" / "开头" / "首" → first item</li>
 *       <li>"中间" → {@code size / 2}</li>
 *     </ul>
 *   </li>
 *   <li><b>Ordinal regex</b> {@code 第?([一二三四五六七八九十1-9])(?:款|个|种|号|件|双)?}
 *       — the unit suffix list is intentionally narrow (no "天" / "次")
 *       so that "第二天到货" never resolves as "the second item".</li>
 *   <li>Otherwise → empty (Orchestrator will ask EmotionAgent to clarify
 *       which item).</li>
 * </ol>
 * <p>
 * Out-of-range ordinals (e.g. "第五款" with 3 items) deliberately return
 * empty — better to re-prompt than to silently snap to the last item.
 */
@Component
public class OrderReferenceResolver {

    private static final Pattern ORDINAL =
            Pattern.compile("第?([一二三四五六七八九十1-9])(?:款|个|种|号|件|双)?");

    private static final Pattern REVERSE = Pattern.compile("倒数|最后第");

    /**
     * Negative-lookahead substrings that disqualify an ordinal match. The
     * regex's optional unit classifier lets "第二" alone resolve, but it also
     * means that "第二天" / "第三次" / "第二页" would silently match the
     * digit and ignore the wrong-domain unit. We scan for these blacklist
     * fragments anywhere in the utterance and refuse to treat the ordinal
     * as a product reference.
     */
    private static final List<String> AMBIGUOUS_ORDINAL_SUFFIXES = List.of(
            "第一天", "第二天", "第三天", "第四天", "第五天", "第六天", "第七天",
            "第一次", "第二次", "第三次", "第四次", "第五次",
            "第一章", "第二章", "第三章",
            "第一页", "第二页", "第三页"
    );

    public Optional<Long> resolve(LastRecommendationsSnapshot snapshot, String utterance) {
        if (snapshot == null || utterance == null || utterance.isBlank()) {
            return Optional.empty();
        }
        List<Long> ids = snapshot.productIds();
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        int size = ids.size();

        // 1. Reverse markers — short-circuit before ordinal so "倒数第二" → last,
        //    not index 1.
        if (REVERSE.matcher(utterance).find()) {
            return Optional.of(ids.get(size - 1));
        }

        // 2. Position keywords. "最后" before "末" (substring containment is fine,
        //    they don't conflict with each other) — order purely for readability.
        if (utterance.contains("最后") || utterance.contains("末")) {
            return Optional.of(ids.get(size - 1));
        }
        if (utterance.contains("第一") || utterance.contains("开头") || utterance.contains("首")) {
            return Optional.of(ids.get(0));
        }
        if (utterance.contains("中间")) {
            return Optional.of(ids.get(size / 2));
        }

        // 3. Ordinal regex. Out-of-range index falls through to empty.
        //    If the utterance contains an ambiguous "第N<unit>" idiom (e.g.
        //    "第二天到货" / "第三次提到") we refuse to interpret the digit
        //    as a product index — the user is talking about a day / pass /
        //    chapter, not a recommendation slot.
        for (String trap : AMBIGUOUS_ORDINAL_SUFFIXES) {
            if (utterance.contains(trap)) {
                return Optional.empty();
            }
        }
        Matcher m = ORDINAL.matcher(utterance);
        if (m.find()) {
            int idx = ordinalToIndex(m.group(1));
            if (idx >= 0 && idx < size) {
                return Optional.of(ids.get(idx));
            }
        }

        return Optional.empty();
    }

    /**
     * Convert a single Chinese / Arabic numeral character to a 0-based index.
     * Returns {@code -1} for unrecognized inputs (defensive — the regex
     * already constrains valid characters).
     */
    int ordinalToIndex(String ord) {
        return switch (ord) {
            case "一", "1" -> 0;
            case "二", "2" -> 1;
            case "三", "3" -> 2;
            case "四", "4" -> 3;
            case "五", "5" -> 4;
            case "六", "6" -> 5;
            case "七", "7" -> 6;
            case "八", "8" -> 7;
            case "九", "9" -> 8;
            case "十"      -> 9;
            default        -> -1;
        };
    }
}
