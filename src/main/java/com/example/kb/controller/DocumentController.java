package com.example.kb.controller;

import com.example.kb.common.R;
import com.example.kb.domain.vo.ChunkPage;
import com.example.kb.domain.vo.DocumentDto;
import com.example.kb.service.IDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档管理 REST 接口。<b>只有站主能访问</b>（AuthInterceptor 拦 /api/documents/**）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有响应走 {@link R}，code=0 成功，非 0 失败（见 GlobalExceptionHandler）。</li>
 *   <li>上传是「先落盘再异步索引」：接口立即返回文档对象，前端轮询状态。</li>
 *   <li>删除要连带删 vector_store 里的向量（无外键），顺序：先向量后表。</li>
 *   <li><b>返回 DTO 不返回实体</b>：{@link DocumentDto} 剔掉了 contentHash 这类内部字段。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 上传落盘目录，可用 rag.upload-dir 覆盖（容器里要挂卷） */
    private final Path uploadDir;
    private final IDocumentService documentService;

    public DocumentController(IDocumentService documentService,
                              @Value("${rag.upload-dir:${java.io.tmpdir}/rag-kb-uploads}") String uploadDir) {
        this.documentService = documentService;
        this.uploadDir = Path.of(uploadDir);
    }

    @GetMapping
    public R list() {
        List<DocumentDto> docs = documentService.listAll();
        return R.ok(docs);
    }

    @GetMapping("/{id}")
    public R detail(@PathVariable long id) {
        Optional<DocumentDto> doc = documentService.findById(id);
        return doc.<R>map(R::ok).orElseGet(() -> R.fail("文档不存在"));
    }

    @GetMapping("/{id}/chunks")
    public R chunks(@PathVariable long id,
                    @RequestParam(defaultValue = "1") int page,
                    @RequestParam(defaultValue = "20") int size) {
        ChunkPage chunkPage = documentService.chunksPage(id, page, size);
        return R.ok(chunkPage);
    }

    /**
     * 上传 PDF / MD / TXT。
     *
     * <p>查重：整篇 SHA-256 命中已有文档时直接返回那个文档（status=READY 的话
     * 前端直接就能用，不用再等一次索引）。</p>
     *
     * <p>⚠️ 落盘到 uploadDir 而不是 /tmp：容器里 /tmp 不挂卷，重启丢文件；
     * 异步索引线程要读这个文件，必须活到索引完成（IIndexingService 负责删）。</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R upload(@RequestParam("file") MultipartFile file) throws IOException {
        String fileType = detectType(file.getOriginalFilename());
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        }
        long docId = documentService.upload(file.getOriginalFilename(), fileType, content, uploadDir);
        // 回完整文档对象而不只是 id：前端拿到就能直接显示文件名/状态，
        // 省一次 GET。文档刚插入，状态一定是 PENDING（或查重命中时的旧状态）。
        return documentService.findById(docId)
                .<R>map(R::ok)
                .orElseGet(() -> R.ok(Map.of("id", docId)));
    }

    @DeleteMapping("/{id}")
    public R delete(@PathVariable long id) {
        documentService.delete(id);
        return R.ok();
    }

    /** 重新索引：删旧向量与 chunk，用原文件重跑一遍 */
    @PostMapping("/{id}/reindex")
    public R reindex(@PathVariable long id) {
        documentService.reindex(id, uploadDir);
        return R.ok();
    }

    static String detectType(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "md";
        }
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        throw new IllegalArgumentException("只支持 pdf / md / txt，收到: " + filename);
    }
}
