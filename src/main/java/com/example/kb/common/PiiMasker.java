package com.example.kb.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人敏感信息脱敏。
 *
 * <h3>为什么需要它</h3>
 * <p>知识库里放的是站主自己的简历，里面有手机号、邮箱、学校。这张站是公开的，
 * 任何访客点一下就能问「简历里的联系方式是什么」。如果原样把切块内容喂给大模型，
 * 模型会一字不差地念出来 —— 这不是模型的问题，是我们把不该给它的东西给它了。</p>
 *
 * <h3>在哪用（两处，缺一不可）</h3>
 * <ol>
 *   <li>{@code ChatServiceImpl.buildPrompt} —— 组装 prompt 前，把每个资料片段的
 *       正文脱敏。这是<b>根</b>：模型看不到，就不可能说出来。</li>
 *   <li>{@code CitationResolverImpl.preview} —— SSE 的 {@code sources} 帧里
 *       回给前端的预览文字。这是<b>面</b>：即便模型守规矩，预览本身是直接
 *       渲染到浏览器上的，不脱敏等于换个地方泄露。</li>
 * </ol>
 *
 * <h3>为什么是「整段替换」而不是「打星号」</h3>
 * <p>{@code 159****3061} 看着像脱敏，实际上仍然告诉阅读者「这里是一个手机号，
 * 前三位是 159」。对搞营销的人来说这已经够用了。更麻烦的是大模型：它看到
 * 掩码后的串，仍然会学着这个模式去拼后面的回答。所以这里直接把整段换成
 * {@code [手机号已隐藏]} 这类占位符 —— 连「这里有个号」这个形状都保留，
 * 但一个可用字符都不剩。</p>
 *
 * <h3>匹配顺序为什么不能乱</h3>
 * <p>邮箱必须先于手机号处理：{@code lyc15935353061@gmail.com} 里就嵌着一个
 * 手机号，先跑手机号规则会把邮箱拆成两半，剩下 {@code lyc[手机号已隐藏]@gmail.com}
 * 这种既不像邮箱也不像手机号的残渣。同理 18 位身份证要先于 16~19 位银行卡，
 * 否则一张身份证会被当成卡号换掉，虽然结果都是屏蔽，但日志里看不出到底命中了哪条规则。</p>
 *
 * <h3>⚠️ 它挡不住什么</h3>
 * <p>正则只能挡「格式规整」的东西。裸写在正文里的微信号、QQ 号、详细门牌号
 * 它都识别不了 —— 那些没有可靠的格式特征，强行匹配会把正常内容误伤。
 * 这一层靠 {@code ChatServiceImpl} 的 SYSTEM_PROMPT 里那条「不得输出联系方式」
 * 兜底，两层一起用。</p>
 */
public final class PiiMasker {

    private static final String MASK_PHONE = "[手机号已隐藏]";
    private static final String MASK_EMAIL = "[邮箱已隐藏]";
    private static final String MASK_ID = "[身份证号已隐藏]";
    private static final String MASK_BANK = "[银行卡号已隐藏]";
    private static final String MASK_TEL = "[电话已隐藏]";
    private static final String MASK_TERM = "[已隐藏]";

    /**
     * 邮箱。故意不用 {@code \w} —— 它包含下划线但也包含中文，会把
     * 「联系方式：lyc@x.com」里的「方式」也吃进去。
     */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /**
     * 18 位身份证。中间的 8 位限定成年份 + 月日，避免把一串随便的 18 位数字
     * （订单号、物流号）误判成身份证。
     */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])");

    /** 15 位旧身份证。现在少见，但简历附件里偶尔还留着 */
    private static final Pattern ID_CARD_15 = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:[0-9]{2})(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}(?![0-9])");

    /** 银行卡 16~19 位。放在身份证之后跑 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])[0-9]{16,19}(?![0-9])");

    /** 中国大陆手机号 */
    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");

    /** 座机，带或不带区号连字符 */
    private static final Pattern LANDLINE = Pattern.compile("(?<![0-9])0[0-9]{2,3}-?[0-9]{7,8}(?![0-9])");

    private PiiMasker() {
    }

    /**
     * 把文本里的手机号、邮箱、身份证、银行卡、座机替换成占位符。
     *
     * <p>幂等：脱敏过的文本再跑一遍不会变化（占位符里没有可匹配的数字串）。</p>
     *
     * @param text 原始文本，允许为 null
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public static String mask(String text) {
        return mask(text, java.util.List.of());
    }

    /**
     * 脱敏，并把配置里的自定义词（姓名、学校等）一并遮蔽。
     *
     * <p>自定义词是<b>字面包含匹配</b>，不区分大小写，按长度从长到短替换 ——
     * 先换长的，避免「山西农业大学」先被「山西」换掉一半，剩下的
     * 「[自定义词已隐藏]农业大学」既不像原文也不像干净文本。</p>
     *
     * @param text   原始文本，允许为 null
     * @param extras 额外要遮蔽的字面词，允许为 null / 空
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public static String mask(String text, java.util.Collection<String> extras) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        // ⚠️ 顺序不可调：邮箱里可能嵌手机号，身份证会被银行卡规则误吞
        s = replace(EMAIL, s, MASK_EMAIL);
        s = replace(ID_CARD, s, MASK_ID);
        s = replace(ID_CARD_15, s, MASK_ID);
        s = replace(BANK_CARD, s, MASK_BANK);
        s = replace(MOBILE, s, MASK_PHONE);
        s = replace(LANDLINE, s, MASK_TEL);
        return replaceTerms(s, extras);
    }

    /**
     * 把自定义词换成占位符。
     *
     * <p>⚠️ 这之后要重新跑一遍正则脱敏吗？不需要，但原因不显然：
     * 自定义词换上去的占位符是 {@code [自定义词已隐藏]}，里面没有数字，
     * 不会凭空造出新的手机号；而原来的手机号在上一步已经被换掉了。
     * 反过来「先换自定义词再跑正则」会出问题 —— 假如某个自定义词本身
     * 长成一串数字，它会被正则当手机号屏蔽，占位符就变成了
     * {@code [手机号已隐藏]}，日志里分不清命中的是规则还是词表。</p>
     */
    private static String replaceTerms(String text, java.util.Collection<String> extras) {
        if (extras == null || extras.isEmpty() || text.isEmpty()) {
            return text;
        }
        java.util.List<String> terms = extras.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        if (terms.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (String term : terms) {
            int from = 0;
            while (true) {
                int at = indexOfIgnoreCase(sb, term, from);
                if (at < 0) {
                    break;
                }
                sb.replace(at, at + term.length(), MASK_TERM);
                from = at + MASK_TERM.length();
            }
        }
        return sb.toString();
    }

    private static int indexOfIgnoreCase(CharSequence haystack, String needle, int from) {
        int last = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            boolean hit = true;
            for (int j = 0; j < needle.length(); j++) {
                char a = Character.toLowerCase(haystack.charAt(i + j));
                char b = Character.toLowerCase(needle.charAt(j));
                if (a != b) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这段文本里是否含有需要脱敏的内容。
     *
     * <p>给调用方做「有没有发生变化」的判断用 —— 比如日志里记一笔
     * 「本次对话有 2 个片段含敏感信息」，或者前端想提示「内容已脱敏」。</p>
     */
    public static boolean containsPii(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return EMAIL.matcher(text).find()
                || ID_CARD.matcher(text).find()
                || ID_CARD_15.matcher(text).find()
                || BANK_CARD.matcher(text).find()
                || MOBILE.matcher(text).find()
                || LANDLINE.matcher(text).find();
    }

    private static String replace(Pattern pattern, String text, String replacement) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        // 用 Matcher.appendReplacement 而不是 String.replaceAll：后者每次都要
        // 重新编译一遍正则，而且 replacement 里的 $ 和 \ 会被当成分组引用。
        // 这里的占位符虽然不含这两个字符，但换个人加个 "$" 就会静默出错。
        StringBuilder sb = new StringBuilder(text.length());
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
