package com.example.kb.dto.site;

import com.example.kb.dto.chat.ChatQuota;
import com.example.kb.dto.media.DownloadDto;
import com.example.kb.dto.media.MomentDto;
import com.example.kb.dto.media.PhotoDto;
import com.example.kb.dto.media.ProjectDto;

import java.util.List;

/**
 * 主页公开信息（{@code GET /api/site} 的 data）。
 *
 * <p>用 record 而不是 Map：字段名改了编译器立刻报错，不会等到浏览器里
 * 出现 undefined 才发现。Jackson 对 record 的支持是原生的，不需要额外注解。</p>
 *
 * <p>⚠️ 为什么照片/项目/简历也塞进这一个接口，而不是让前端调 4 个接口：</p>
 * <ol>
 *   <li>主页首屏一次请求拿全，少 3 个 RTT。手机 4G 下每次 RTT 30-80ms，
 *       4 个串行请求就是多 100-240ms 白屏</li>
 *   <li>这四块数据本来就一起渲染，分开拿还会出现「照片出来了但项目还没来」
 *       的跳动</li>
 *   <li>数据量可控：照片走 visible 且上限 60 条，只返回 URL 和说明</li>
 * </ol>
 *
 * @param title           站名（浏览器标题 + 页脚）
 * @param owner           站主资料
 * @param documentCount   资料篇数
 * @param chunkCount      知识片段总数
 * @param recentDocuments 最近收录的几篇（只含文件名/块数/类型）
 * @param visitCount      累计访问次数
 * @param quota           当前访客的问答额度
 * @param photos          照片墙（只含 visible=true）
 * @param projects        项目展示（只含 visible=true）
 * @param downloads       简历/作品集下载（只含 visible=true）
 * @param moments         最新几条日常动态（只含 visible=true，且<b>不含评论数</b>）
 */
public record SiteInfo(String title,
                       SiteOwner owner,
                       long documentCount,
                       long chunkCount,
                       List<RecentDocument> recentDocuments,
                       long visitCount,
                       ChatQuota quota,
                       List<PhotoDto> photos,
                       List<ProjectDto> projects,
                       List<DownloadDto> downloads,
                       List<MomentDto> moments) {
}
