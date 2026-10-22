package com.example.kb.service;

import com.example.kb.config.SiteProperties;
import com.example.kb.domain.po.KbProfile;
import com.example.kb.domain.vo.LinkDto;
import com.example.kb.domain.vo.ProfileDto;
import com.example.kb.domain.vo.SiteOwner;
import com.example.kb.mapper.KbProfileMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public interface IProfileService {

    /** 一个临时的入参形态，避免 Service 依赖 DTO 包的内部类 */
    public record LinkInputLike(String label, String url) {
    }

    /**
     * 解析当前生效的资料（数据库优先，配置兜底）。
     *
     * <p>主页和后台编辑页都调这个 —— 保证两边看到的是同一份数据，
     * 不会出现「后台显示 A、主页显示 B」。</p>
     */
    public ProfileDto resolve();

    /** 主页首屏用的精简版（少一个 fromConfig / updatedAt，不需要给访客看） */
    public SiteOwner owner();

    /** 站主保存「关于我」。整行覆盖，见类注释里说的理由 */
    public ProfileDto save(String owner, String avatarText, String avatarUrl, String tagline, String intro, List<String> about, List<LinkInputLike> links, List<String> tags);

    /**
     * 删掉数据库里的资料行，回到「听配置文件」的状态。
     *
     * <p>用途：站主把 yml 改好之后，发现数据库的旧值盖着不生效，
     * 需要一个「恢复成配置」的出口。不加这个功能的话，
     * 站主只能自己 psql 去 DELETE，对一个「个人网站」来说太硬核了。</p>
     */
    public ProfileDto resetToConfig();

}
