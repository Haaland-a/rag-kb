package com.example.kb.service;

import com.example.kb.config.ChatPoolProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：模型池的轮转顺序与失败分类。
 *
 * <p>这里测的是两件<b>逻辑正确性</b>，都和网络无关：</p>
 * <ol>
 *   <li><b>轮转是否真的均摊</b> —— 如果每次永远返回同一个模型，
 *       「池化」就退化成「只用第一个」，第一个额度打光后全站挂掉。</li>
 *   <li><b>403 额度耗尽是否被认出来</b> —— 认错了就会被当成「一般错误」
 *       冷却 5 分钟，5 分钟后又去撞同一个用光的模型，用户反复失败。</li>
 * </ol>
 *
 * <p>不启 Spring 上下文、不发网络请求：用反射构造 {@link ChatModelPool}，
 * 直接喂异常对象验证分类结果。</p>
 */
class ChatModelPoolTest {

    /**
     * 造一个可用的池子。
     *
     * <p>⚠️ 不能用 {@code new ChatModelPool(...)}：构造器要
     * {@code OpenAiApi} 与 {@code ToolCallingManager}，一个是真 HTTP 客户端、
     * 一个要 Spring 上下文。这里用 {@code sun.misc.Unsafe} 跳过构造器，
     * 再手工填 {@code slots} / {@code properties} / {@code cursor} 三个字段
     * —— 被测的 {@code order()} 与 {@code classify()} 只依赖这三个。</p>
     */
    private static ChatModelPool newPool(String... modelNames) throws Exception {
        ChatModelPool pool = (ChatModelPool) unsafe().allocateInstance(ChatModelPool.class);

        Map<String, Object> slots = new LinkedHashMap<>();
        set(pool, "slots", slots);
        set(pool, "properties", new ChatPoolProperties());
        set(pool, "cursor", new AtomicInteger());

        Class<?> modelCls = Class.forName("com.example.kb.config.ChatPoolProperties$Model");
        Class<?> slotCls = Class.forName("com.example.kb.service.ChatModelPool$Slot");
        Constructor<?> modelCtor = modelCls.getDeclaredConstructor();
        Constructor<?> slotCtor = slotCls.getDeclaredConstructor(modelCls, boolean.class);
        modelCtor.setAccessible(true);
        slotCtor.setAccessible(true);

        for (String name : modelNames) {
            Object m = modelCtor.newInstance();
            modelCls.getMethod("setName", String.class).invoke(m, name);
            modelCls.getMethod("setSupportsDisableThinking", boolean.class).invoke(m, true);
            slots.put(name, slotCtor.newInstance(m, true));
        }
        return pool;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = ChatModelPool.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("轮转：连续调用 6 次，每个模型都当过 2 次第一")
    void rotationIsEven() throws Exception {
        ChatModelPool pool = newPool("a", "b", "c");

        List<String> firsts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            firsts.add(pool.order().get(0));
        }
        assertEquals(6, firsts.size());
        for (String m : List.of("a", "b", "c")) {
            assertEquals(2, Collections.frequency(firsts, m),
                    "模型 " + m + " 应该均摊到 2 次，实际顺序：" + firsts);
        }
    }

    @Test
    @DisplayName("轮转：每次返回的都是全集，只是顺序不同")
    void orderAlwaysContainsAll() throws Exception {
        ChatModelPool pool = newPool("a", "b", "c");
        for (int i = 0; i < 4; i++) {
            List<String> order = pool.order();
            assertEquals(3, order.size());
            assertTrue(order.containsAll(List.of("a", "b", "c")));
        }
    }

    @Test
    @DisplayName("分类：403 额度报文 → QUOTA")
    void classifyQuota() throws Exception {
        Method m = ChatModelPool.class.getDeclaredMethod("classify", Throwable.class);
        m.setAccessible(true);

        RuntimeException real = new RuntimeException(
                "403 Forbidden from POST /v1/chat/completions",
                new IllegalStateException(
                        "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\","
                                + "\"message\":\"Free quota exhausted.\"}}"));
        assertEquals("QUOTA", m.invoke(null, real).toString());

        assertEquals("QUOTA", m.invoke(null,
                new RuntimeException("insufficient_quota")).toString());
    }

    @Test
    @DisplayName("分类：429 → RATE_LIMIT；模型名写错 → OTHER")
    void classifyOthers() throws Exception {
        Method m = ChatModelPool.class.getDeclaredMethod("classify", Throwable.class);
        m.setAccessible(true);

        assertEquals("RATE_LIMIT", m.invoke(null,
                new RuntimeException("429 Too Many Requests")).toString());
        assertEquals("OTHER", m.invoke(null,
                new RuntimeException("model not found: foo")).toString());
    }

    @Test
    @DisplayName("失败上报后该模型进入冷却，order 里排到最后；恢复后回到池中")
    void failedModelGoesToBack() throws Exception {
        ChatModelPool pool = newPool("a", "b");

        pool.reportFailure("a", new RuntimeException("boom"));
        assertEquals("b", pool.order().get(0), "a 冷却后 b 应该排第一");

        pool.reportSuccess("a");
        assertTrue(pool.order().contains("a"), "恢复后 a 应该回到池中");
    }

    @Test
    @DisplayName("额度耗尽的冷却时间远长于一般错误")
    void quotaCooldownLongerThanError() throws Exception {
        ChatModelPool pool = newPool("q", "e");
        ChatPoolProperties props = new ChatPoolProperties();

        pool.reportFailure("q", new RuntimeException("AllocationQuota.FreeTierOnly"));
        pool.reportFailure("e", new RuntimeException("model not found"));

        long quotaCd = props.getQuotaCooldownSeconds();
        long errorCd = props.getErrorCooldownSeconds();
        assertTrue(quotaCd > errorCd * 10,
                "额度冷却应远长于一般错误，否则会反复撞同一个用光的模型");
        assertEquals(21600, quotaCd);
        assertEquals(300, errorCd);
    }
}
