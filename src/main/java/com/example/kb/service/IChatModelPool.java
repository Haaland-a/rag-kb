package com.example.kb.service;

import com.example.kb.config.ChatPoolProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;

public interface IChatModelPool {

    /** 一个模型的状态快照，给后台展示 */
    public record ModelStatus(String model,
                              boolean enabled,
                              boolean supportsDisableThinking,
                              boolean supportsTemperature,
                              boolean available,
                              long cooldownSeconds,
                              int failCount,
                              String lastErrorKind,
                              String lastError) {
    }

    /**
     * 给出这次请求的模型尝试顺序。
     *
     * <p>规则：可用的按轮转起点排前面，冷却中的排最后（万一全挂了，宁可碰一次
     * 冷却中的也别直接给用户 503）。</p>
     */
    public List<String> order();

    /** 取某个模型的 ChatClient（首次调用时构造并缓存） */
    public ChatClient clientFor(String modelName);

    /**
     * 上报一次失败，进入冷却。
     *
     * @param modelName 出问题的模型
     * @param error     原始异常（用来分类）
     */
    public void reportFailure(String modelName, Throwable error);

    /** 上报一次成功，清掉冷却与错误信息 */
    public void reportSuccess(String modelName);

    /** 池子当前状态，给站主后台看 */
    public List<ModelStatus> status();

    public void probeExpired();

}
