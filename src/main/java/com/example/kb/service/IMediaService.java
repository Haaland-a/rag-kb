package com.example.kb.service;

import com.example.kb.domain.po.KbDownload;
import com.example.kb.domain.po.KbPhoto;
import com.example.kb.domain.po.KbProject;
import com.example.kb.domain.vo.DownloadDto;
import com.example.kb.domain.vo.PhotoDto;
import com.example.kb.domain.vo.ProjectDto;
import com.example.kb.infrastructure.storage.FileStorage;
import com.example.kb.mapper.KbDownloadMapper;
import com.example.kb.mapper.KbPhotoMapper;
import com.example.kb.mapper.KbProjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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

public interface IMediaService {

    /** 站主视角：全部照片（含隐藏） */
    public List<PhotoDto> listPhotosForAdmin();

    /** 访客视角：只返回可见的，主页用 */
    public List<PhotoDto> listVisiblePhotos();

    /**
     * 上传一张照片。
     *
     * @param caption 一句话说明，可为空
     */
    public PhotoDto uploadPhoto(MultipartFile file, String caption) throws IOException;

    /**
     * 上传站主头像，返回可直接写进 {@code kb_profile.avatar_url} 的 URL。
     *
     * <p>⚠️ 单独开一个 {@code avatars/} 目录，不和照片墙混在一起：
     * 照片墙是「可批量管理的相册」，头像是「一个固定的资源」。
     * 混在一起以后想「清空照片墙」会把头像一起删掉。</p>
     *
     * <p>⚠️ 尺寸不裁：前端用 {@code object-fit: cover} 圆形裁切，
     * 服务端不引图像处理（2C2G 上为一头像开 BufferedImage 变换不划算，
     * 而且 ImageIO 处理 PNG 透明通道容易出意外）。代价是原图多大就存多大，
     * 所以这里把上限压到 8MB 并提示站主。</p>
     */
    public String uploadAvatar(MultipartFile file) throws IOException;

    /** 改说明 / 排序 / 可见性 */
    public void updatePhoto(long id, String caption, Integer sortOrder, Boolean visible);

    /**
     * 删除照片。
     *
     * <p>顺序：先删数据库行，再删存储里的文件。理由见类注释。</p>
     */
    public void deletePhoto(long id);

    public List<ProjectDto> listProjectsForAdmin();

    public List<ProjectDto> listVisibleProjects();

    /**
     * 上传项目截图，返回可放进 coverUrl 的地址。
     *
     * <p>⚠️ 单独一个方法而不是复用 {@link #uploadPhoto}：截图存到
     * {@code projects/} 目录下，和照片墙分开。混在一起的话，
     * 以后想「清空照片墙」会连带删掉项目截图。</p>
     */
    public String uploadProjectCover(MultipartFile file) throws IOException;

    /** 新建项目卡片 */
    public long createProject(String title, String description, String coverUrl, String tech, String repoUrl, String demoUrl, Integer sortOrder, Boolean visible);

    /**
     * 修改项目卡片。
     *
     * <p>⚠️ 整体覆盖：传 null 就是清空那个字段。这是有意为之 ——
     * 项目卡片要能「删掉截图」，局部更新做不到这一点。</p>
     */
    public void updateProject(long id, String title, String description, String coverUrl, String tech, String repoUrl, String demoUrl, Integer sortOrder, Boolean visible);

    public void deleteProject(long id);

    public List<DownloadDto> listDownloadsForAdmin();

    public List<DownloadDto> listVisibleDownloads();

    /** 上传简历 PDF，落库并返回完整条目 */
    public DownloadDto uploadDownload(MultipartFile file, String title) throws IOException;

    /** 改标题 / 排序 / 可见性 */
    public void updateDownload(long id, String title, Integer sortOrder, Boolean visible);

    public void deleteDownload(long id);

    /**
     * 记一次下载。
     *
     * <p>⚠️ 只在站主自己也不下载时才记？不 —— 访客点下载就走这里，
     * 站主在后台点「预览」不经过这个接口。计数偏一点无所谓，
     * 站主只是想大概知道有没有人下。</p>
     */
    public void recordDownload(long id);

    /** 公开可下载项的地址（校验可见性后再返回，防止直接猜 id 下隐藏文件） */
    public String visibleDownloadUrl(long id);

}
