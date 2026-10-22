package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.DownloadDto;
import com.example.kb.domain.dto.MediaRequests;
import com.example.kb.domain.vo.PhotoDto;
import com.example.kb.domain.vo.ProjectDto;
import com.example.kb.service.IMediaService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 个人内容管理接口：照片墙 / 项目展示 / 简历下载。
 *
 * <p><b>权限分两档：</b></p>
 * <ul>
 *   <li>{@code GET /api/media/public/**} —— 任何人可访问，主页用</li>
 *   <li>其余全部 —— 只有站主能访问（AuthInterceptor 拦 /api/media 前缀）</li>
 * </ul>
 *
 * <p>⚠️ 为什么公开接口单独放 {@code /public} 子路径而不是「同一个 GET 按登录状态返回不同数据」：
 * 后者一旦拦截器配置有误，隐藏内容就会漏出去；而两个路径在代码上是两份独立的方法，
 * 漏一个只影响一个视图，且能靠路径白名单一眼审出来。</p>
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final IMediaService mediaService;

    public MediaController(IMediaService mediaService) {
        this.mediaService = mediaService;
    }

    // ==================== 公开（访客） ====================

    /** 主页照片墙。只返回 visible=true 的 */
    @GetMapping("/public/photos")
    public R publicPhotos() {
        List<PhotoDto> photos = mediaService.listVisiblePhotos();
        return R.ok(photos);
    }

    /** 主页项目区。只返回 visible=true 的 */
    @GetMapping("/public/projects")
    public R publicProjects() {
        List<ProjectDto> projects = mediaService.listVisibleProjects();
        return R.ok(projects);
    }

    /** 主页下载区。只返回 visible=true 的 */
    @GetMapping("/public/downloads")
    public R publicDownloads() {
        List<DownloadDto> downloads = mediaService.listVisibleDownloads();
        return R.ok(downloads);
    }

    /**
     * 记一次下载。
     *
     * <p>公开接口：访客点下载时前端调一次。计数不是精确审计，丢一次无所谓。</p>
     */
    @PostMapping("/public/downloads/{id}/hit")
    public R hitDownload(@PathVariable long id) {
        mediaService.recordDownload(id);
        return R.ok();
    }

    // ==================== 站主：照片墙 ====================

    /** 全部照片（含隐藏） */
    @GetMapping("/photos")
    public R adminPhotos() {
        List<PhotoDto> photos = mediaService.listPhotosForAdmin();
        return R.ok(photos);
    }

    /** 上传照片 */
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R uploadPhoto(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "caption", required = false) String caption)
            throws IOException {
        PhotoDto photo = mediaService.uploadPhoto(file, caption);
        return R.ok(photo);
    }

    /** 改说明 / 排序 / 可见性 */
    @PutMapping("/photos/{id}")
    public R updatePhoto(@PathVariable long id,
                         @Valid @RequestBody MediaRequests.PhotoUpdate body) {
        mediaService.updatePhoto(id, body.getCaption(), body.getSortOrder(), body.getVisible());
        return R.ok();
    }

    @DeleteMapping("/photos/{id}")
    public R deletePhoto(@PathVariable long id) {
        mediaService.deletePhoto(id);
        return R.ok();
    }

    // ==================== 站主：项目展示 ====================

    @GetMapping("/projects")
    public R adminProjects() {
        List<ProjectDto> projects = mediaService.listProjectsForAdmin();
        return R.ok(projects);
    }

    /** 上传项目截图，返回 URL（前端拿到后塞进表单的 coverUrl 再保存项目） */
    @PostMapping(value = "/projects/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R uploadProjectCover(@RequestParam("file") MultipartFile file) throws IOException {
        String url = mediaService.uploadProjectCover(file);
        return R.ok(java.util.Map.of("url", url));
    }

    @PostMapping("/projects")
    public R createProject(@Valid @RequestBody MediaRequests.ProjectSave body) {
        long id = mediaService.createProject(body.getTitle(), body.getDescription(),
                body.getCoverUrl(), body.getTech(), body.getRepoUrl(), body.getDemoUrl(),
                body.getSortOrder(), body.getVisible());
        return R.ok(java.util.Map.of("id", id));
    }

    @PutMapping("/projects/{id}")
    public R updateProject(@PathVariable long id,
                           @Valid @RequestBody MediaRequests.ProjectSave body) {
        mediaService.updateProject(id, body.getTitle(), body.getDescription(),
                body.getCoverUrl(), body.getTech(), body.getRepoUrl(), body.getDemoUrl(),
                body.getSortOrder(), body.getVisible());
        return R.ok();
    }

    @DeleteMapping("/projects/{id}")
    public R deleteProject(@PathVariable long id) {
        mediaService.deleteProject(id);
        return R.ok();
    }

    // ==================== 站主：简历下载 ====================

    @GetMapping("/downloads")
    public R adminDownloads() {
        List<DownloadDto> downloads = mediaService.listDownloadsForAdmin();
        return R.ok(downloads);
    }

    /** 上传简历 / 作品集 */
    @PostMapping(value = "/downloads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R uploadDownload(@RequestParam("file") MultipartFile file,
                            @RequestParam(value = "title", required = false) String title)
            throws IOException {
        DownloadDto d = mediaService.uploadDownload(file, title);
        return R.ok(d);
    }

    @PutMapping("/downloads/{id}")
    public R updateDownload(@PathVariable long id,
                            @Valid @RequestBody MediaRequests.DownloadUpdate body) {
        mediaService.updateDownload(id, body.getTitle(), body.getSortOrder(), body.getVisible());
        return R.ok();
    }

    @DeleteMapping("/downloads/{id}")
    public R deleteDownload(@PathVariable long id) {
        mediaService.deleteDownload(id);
        return R.ok();
    }

    /**
     * 取可下载的地址。
     *
     * <p>为什么不让前端直接用 {@code /public/downloads} 里的 url 而是多走一步：
     * 前端拿到的 url 是可见文件，但有人可能直接猜一个隐藏项的 url。
     * 走这个接口能校验可见性。（本地存储时文件是静态暴露的，这条路挡不住；
     * 切 OSS 私有 bucket 后才真正有效。现在先保留接口，将来改造只需改存储层。）</p>
     */
    @GetMapping("/public/downloads/{id}/url")
    public R publicDownloadUrl(@PathVariable long id) {
        String url = mediaService.visibleDownloadUrl(id);
        return R.ok(java.util.Map.of("url", url));
    }
}
