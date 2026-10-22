package com.example.kb.common;

import java.util.Collection;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 需要额外屏蔽的词（姓名、学校、公司、住址等）。
 *
 * <p>正则只能挡「格式规整」的东西 —— 手机号、邮箱、身份证有固定形状，写个
 * 正则就能抓。姓名和学校没有形状：{@code 李宇超}、{@code 山西农业大学}
 * 在文本里就是几个普通汉字，靠正则识别要么抓不到，要么把正常内容一起误伤。</p>
 *
 * <p>所以这一层改成「由站主自己列出来」，配在 {@code application-prod.yml}：</p>
 *
 * <pre>
 * rag:
 *   pii:
 *     extra-terms:
 *       - "李宇超"
 *       - "山西农业大学"
 * </pre>
 *
 * <p>⚠️ 这个词表<b>只在服务端配置里</b>：写进仓库的 {@code application.yml}
 * 只放空数组，否则公开仓库里就能看到站主真名 —— 那等于脱敏表本身成了泄露源。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.pii")
public class PiiProperties {

    /**
     * 额外要屏蔽的字面词。空数组 = 只做正则脱敏。
     *
     * <p>匹配是「包含即替换」（不区分大小写），所以别放太短的词 ——
     * 填一个「李」会把所有姓李的名字和「李宇超」以外的词也换掉。</p>
     */
    private Collection<String> extraTerms = java.util.List.of();

    public Collection<String> getExtraTerms() {
        return extraTerms;
    }

    public void setExtraTerms(Collection<String> extraTerms) {
        this.extraTerms = extraTerms == null ? java.util.List.of() : extraTerms;
    }
}
