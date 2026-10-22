package com.example.kb.service;

import com.example.kb.domain.dto.SiteContentRequests;
import com.example.kb.domain.po.KbMoment;
import com.example.kb.domain.po.KbPhoto;
import com.example.kb.domain.vo.CommentDto;
import com.example.kb.domain.vo.MomentDetail;
import com.example.kb.domain.vo.MomentDto;
import com.example.kb.domain.vo.MomentPage;
import com.example.kb.domain.vo.MomentPreview;
import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.mapper.KbCommentMapper;
import com.example.kb.mapper.KbMomentMapper;
import com.example.kb.mapper.KbPhotoMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

public interface IMomentService {

    /**
     * 上传媒体的结果。
     *
     * @param url       存储 URL，直接放进 MomentSave.mediaUrl
     * @param mediaType IMAGE / VIDEO
     * @param width     图片宽，视频为 null
     * @param height    图片高，视频为 null
     * @param size      字节数
     */
    public record UploadedMedia(String url, String mediaType, Integer width, Integer height, Long size) {
    }

    /**
     * 上传动态的媒体文件（图片或视频），返回 URL 和宽高。
     *
     * <p>⚠️ 分成「先传文件拿到 URL，再提交动态」两步，而不是一个 multipart 请求
     * 把文字和文件一起提交：后者在上传 50MB 视频时，用户只要在等的时候
     * 关了页面，整个请求就白费了；两步的话文件已经在存储里，
     * 用户填完文字还能继续提交。代价是会留下没人引用的孤儿文件，
     * 由站主在 OSS 控制台清（个人站的量级不值得加定时清理任务）。</p>
     */
    public UploadedMedia uploadMedia(MultipartFile file);

    /**
     * 发布一条动态。
     *
     * @param syncToPhotos 是否顺便发一条到照片墙（仅新建生效）
     * @param photoCaption 照片墙里那条的说明，空则用文字内容截断
     */
    public MomentDto create(SiteContentRequests.MomentSave req, boolean syncToPhotos);

    /**
     * 编辑动态的文字和可见性。
     *
     * <p>⚠️ 不支持改媒体：见 {@link KbMomentMapper#updateContent} 的注释。
     * 也不支持在这里打开「同步到照片墙」—— 补发照片需要重新上传文件。</p>
     */
    public void update(long id, String content, Boolean visible);

    /**
     * 把一条已有的动态补发到照片墙。
     *
     * <p>⚠️ 这个能力是单独加的，因为 {@link #update} 做不到 ——
     * 站主经常是发完才发现「这张应该也放照片墙」。
     * 补发只对图片动态有效，且不会重复补发（已同步的直接返回原 photoId）。</p>
     *
     * @return 照片墙记录 id
     */
    public long syncToPhotos(long id, String photoCaption);

    /**
     * 删动态。
     *
     * <p>⚠️ 顺序：先删数据库行（评论靠外键级联一起走），再删媒体文件，
     * 最后删照片墙那条。理由见 {@code IMediaService} 类注释 ——
     * 先删文件的话，写库失败会留下指向空文件的记录，页面全是裂图且无法恢复。</p>
     */
    public void delete(long id);

    /** 照片墙里那张图被单独删掉时，把动态上的引用置空 */
    public void onPhotoDeleted(long photoId);

    /** 站主视角：全部动态（含隐藏）+ 评论数 */
    public MomentPage listForAdmin(int limit, int offset);

    /**
     * 访客视角：只返回可见的，且<b>不带评论数</b>。
     *
     * <p>⚠️ 这就是「评论只有站主能看到」这条需求在数据层的落地：
     * 不是前端隐藏一个数字，而是服务端根本不查、不返回。
     * 抓包也看不到。</p>
     *
     * <p>⚠️ 但 {@code private} 留言的<b>预览</b>是另一回事（见
     * {@link #listForVisitorWithPreviews}）—— 公开留言本来就是给所有人看的，
     * 主页给个预览不违反「悄悄话只有站主能看」，因为 SQL 已经把 PRIVATE
     * 和 HIDDEN 排除干净了。两个方法的区别只在于「要不要顺带查留言」，
     * 不是可见性口径不同。</p>
     */
    public List<MomentDto> listForVisitor();

    /**
     * 访客详情页的数据：一条动态 + 它下面访客可见的全部留言。
     *
     * <p>⚠️ 返回 {@code Optional.empty()} 有三种情况，前端都当 404 处理：
     * id 不存在、动态被站主设成了「仅自己可见」、动态已删。
     * <b>不区分这三种</b> —— 区分了等于告诉外面「这个 id 存在但被藏了」。</p>
     *
     * @param viewerId 看的人（null = 游客），决定「自己发的被屏蔽留言」是否可见
     */
    public Optional<MomentDetail> findForVisitor(long momentId, Long viewerId);

    /**
     * 主页数据：动态列表 + 每条动态的留言预览 + 留言总数。
     *
     * <p>为什么单独一个方法而不是让 {@code ISiteService} 自己拼两次调用：
     * 「先查动态、再按这批 id 批量查留言」的顺序和 id 提取是一次事务性的
     * 读逻辑，分散到两个类里将来改一处忘另一处就会错位。</p>
     *
     * <p>⚠️ 预览条数（{@link #HOME_PREVIEW_LIMIT}）和总数是两回事：
     * 主页每条动态下面只摊开 {{@value #HOME_PREVIEW_LIMIT}} 条，
     * 但角标要显示真实总数，点进详情页才看全部。</p>
     *
     * @param viewerId 看的人（null = 游客）
     */
    public List<MomentPreview> listForVisitorWithPreviews(Long viewerId);

}
