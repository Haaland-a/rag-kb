package com.example.kb.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PiiMasker} 的行为测试。
 *
 * <p>这些用例的文本全部是**构造的假数据**（159 开头但后面随意、example.com 域名），
 * 不是真实个人信息。测试文件会进公开仓库，写真人手机号进去等于又泄露一次。</p>
 */
class PiiMaskerTest {

    @Test
    @DisplayName("手机号整段替换，不留前三位")
    void masksMobile() {
        String out = PiiMasker.mask("联系方式：15900001111，随时可以打");
        assertEquals("联系方式：[手机号已隐藏]，随时可以打", out);
        // ⚠️ 关键断言：脱敏后连「159」都不该出现。
        //    如果哪天有人把规则改成 159****1111 这种掩码，这条会红。
        assertFalse(out.contains("159"));
        assertFalse(out.contains("1111"));
    }

    @Test
    @DisplayName("邮箱先于手机号处理，不会把嵌在邮箱里的号码拆出来")
    void masksEmailBeforeMobile() {
        String out = PiiMasker.mask("邮箱 lyc15900001111@example.com 可以联系");
        // 整个邮箱被换掉，不会剩下 `lyc[手机号已隐藏]@example.com` 这种残渣
        assertEquals("邮箱 [邮箱已隐藏] 可以联系", out);
    }

    @Test
    @DisplayName("18 位身份证被识别，且不会被银行卡规则先吃掉")
    void masksIdCard() {
        // 110101 + 19900101 + 123 + 1  = 18 位，校验位随便写
        String out = PiiMasker.mask("身份证 110101199001011231 已核验");
        assertEquals("身份证 [身份证号已隐藏] 已核验", out);
    }

    @Test
    @DisplayName("16~19 位纯数字按银行卡处理")
    void masksBankCard() {
        String out = PiiMasker.mask("卡号 6222021234567890123 已绑定");
        assertEquals("卡号 [银行卡号已隐藏] 已绑定", out);
    }

    @Test
    @DisplayName("座机号被识别")
    void masksLandline() {
        String out = PiiMasker.mask("单位电话 0351-1234567");
        assertEquals("单位电话 [电话已隐藏]", out);
    }

    @Test
    @DisplayName("普通数字不受影响：年份、页码、短编号、小数")
    void keepsOrdinaryNumbers() {
        String src = "2022-2026 年间处理了 128 笔订单，第 3 页，耗时 1.5 秒，端口 8080";
        assertEquals(src, PiiMasker.mask(src));
    }

    @Test
    @DisplayName("幂等：脱敏过的文本再跑一遍不变")
    void isIdempotent() {
        String once = PiiMasker.mask("手机 15900001111 邮箱 a@example.com");
        String twice = PiiMasker.mask(once);
        assertEquals(once, twice);
    }


    @Test
    @DisplayName("自定义词（姓名/学校）能按字面遮蔽，且长词优先")
    void masksExtraTerms() {
        java.util.List<String> terms = java.util.List.of("李宇超", "山西农业大学");
        String out = PiiMasker.mask("李宇超 山西农业大学 本科", terms);
        assertEquals("[已隐藏] [已隐藏] 本科", out);
        // 长词优先生效：如果先换了「山西」，就会留下「[已隐藏]农业大学」
        assertFalse(out.contains("农业大学"));
    }

    @Test
    @DisplayName("自定义词大小写不敏感，且空词表不影响正则脱敏")
    void extraTermsAreCaseInsensitive() {
        assertEquals("[已隐藏]", PiiMasker.mask("GitHub", java.util.List.of("github")));
        // 空词表 = 退回纯正则脱敏，手机号照样被换掉
        assertEquals("[手机号已隐藏]", PiiMasker.mask("15900001111", java.util.List.of()));
        assertEquals("[手机号已隐藏]", PiiMasker.mask("15900001111", null));
    }

    @Test
    @DisplayName("null 与空串原样返回")
    void handlesNullAndEmpty() {
        assertNull(PiiMasker.mask(null));
        assertEquals("", PiiMasker.mask(""));
    }

    @Test
    @DisplayName("containsPii 能判断出有没有敏感信息")
    void detectsPii() {
        assertTrue(PiiMasker.containsPii("打 15900001111"));
        assertTrue(PiiMasker.containsPii("a@example.com"));
        assertFalse(PiiMasker.containsPii("就是用 Java 写了个知识库，跑在 2C2G 上"));
    }

    @Test
    @DisplayName("占位符里不含可被再次匹配的字符")
    void placeholdersAreStable() {
        String out = PiiMasker.mask("15900001111");
        assertEquals("[手机号已隐藏]", out);
        assertFalse(PiiMasker.containsPii(out));
    }
}
