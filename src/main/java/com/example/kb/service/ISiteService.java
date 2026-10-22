package com.example.kb.service;

import com.example.kb.config.SiteProperties;
import com.example.kb.domain.po.KbDocument;
import com.example.kb.domain.vo.ChatQuota;
import com.example.kb.domain.vo.DownloadDto;
import com.example.kb.domain.vo.MomentPreview;
import com.example.kb.domain.vo.PhotoDto;
import com.example.kb.domain.vo.ProjectDto;
import com.example.kb.domain.vo.RecentDocument;
import com.example.kb.domain.vo.SiteInfo;
import com.example.kb.domain.vo.SiteOwner;
import com.example.kb.infrastructure.ratelimit.IpRateLimiter;
import com.example.kb.mapper.KbDocumentMapper;
import com.example.kb.mapper.KbVisitMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public interface ISiteService {

    /**
     * 主页所需的全部公开数据，一次组装完。
     *
     * @param ip       当前访客 IP（算剩余额度用）
     * @param viewerId 当前看的人（null = 游客）；只用来决定「自己发的被屏蔽
     *                 留言是否可见」，不区分站主 —— 主页对谁都一样
     * @param isAdmin  是否站主（站主额度不限，显示 -1）
     */
    public SiteInfo build(String ip, boolean isAdmin, Long viewerId);

}
