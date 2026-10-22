package com.example.kb.service.impl;

import com.example.kb.common.PiiMasker;
import com.example.kb.common.PiiProperties;
import com.example.kb.domain.po.KbChunk;
import com.example.kb.domain.vo.SourceRef;
import com.example.kb.infrastructure.vectorstore.VectorStoreGateway;
import com.example.kb.service.ICitationResolver;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class CitationResolverImpl implements ICitationResolver {

    /** preview 截断长度，SSE 帧别太大 */
    private static final int PREVIEW_LIMIT = 200;

    private final PiiProperties piiProperties;

    public CitationResolverImpl(PiiProperties piiProperties) {
        this.piiProperties = piiProperties;
    }

    /**
     * @param documents VectorStoreGateway.search 的返回
     * @param chunkById 可选：按 chunkId 索引的 kb_chunk 行，用于兜底回查
     */
    public List<SourceRef> resolve(List<Document> documents,
                                   Map<Long, KbChunk> chunkById) {
        return documents.stream()
                .map(doc -> toSourceRef(doc, chunkById))
                .toList();
    }

    private SourceRef toSourceRef(Document doc,
                                  Map<Long, KbChunk> chunkById) {
        Map<String, Object> meta = doc.getMetadata();
        SourceRef ref = new SourceRef();

        Long chunkId = longOrNull(meta.get(VectorStoreGateway.META_CHUNK_ID));
        Long documentId = longOrNull(meta.get(VectorStoreGateway.META_DOCUMENT_ID));
        ref.setChunkId(chunkId);
        ref.setDocumentId(documentId);
        ref.setFilename(strOrNull(meta.get(VectorStoreGateway.META_FILENAME)));
        ref.setPageNumber(intOrNull(meta.get(VectorStoreGateway.META_PAGE_NUMBER)));
        ref.setHeadingPath(strOrNull(meta.get(VectorStoreGateway.META_HEADING_PATH)));

        KbChunk row = chunkId == null ? null : chunkById.get(chunkId);
        if (row != null) {
            // 兜底：metadata 缺字段时用结构化列补
            if (ref.getFilename() == null) {
                ref.setFilename(row.getFilename());
            }
            if (ref.getPageNumber() == null) {
                ref.setPageNumber(row.getPageNumber());
            }
            if (ref.getHeadingPath() == null) {
                ref.setHeadingPath(row.getHeadingPath());
            }
        }

        ref.setScore(scoreOf(doc));
        ref.setPreview(preview(doc.getText()));

        // ⚠️ 文件名和标题路径也必须脱敏 —— 这两个字段是**原样回给前端**的，
        // 前端直接渲染在引用卡片上。实测就踩过：正文脱敏做对了，但文件名
        // 叫「张三.pdf」、标题路径里带「某某大学」，访客点开引用照样
        // 看得到。文件名这种「元数据」最容易漏，因为写的时候想的是「这是
        // 文件名不是内容」，但从泄露角度看它和内容没有区别。
        ref.setFilename(maskText(ref.getFilename()));
        ref.setHeadingPath(maskText(ref.getHeadingPath()));
        return ref;
    }

    /** 把一段元数据文本按同一套规则脱敏；null 原样返回 */
    private String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return PiiMasker.mask(text, piiProperties.getExtraTerms());
    }

    /** PgVectorStore 的 DocumentRowMapper 把 SQL 的 distance 列读进来放在 score 位上 */
    private static Double scoreOf(Document doc) {
        Double score = doc.getScore();
        if (score == null) {
            return null;
        }
        // COSINE_DISTANCE 的 SQL 是 `embedding <=> ? AS distance`，
        // DocumentRowMapper 直接把这个 distance 塞进了 score。转成相似度。
        return 1.0 - score;
    }

    /**
     * 生成引用预览。
     *
     * <p>⚠️ 必须先脱敏再截断，不能反过来：先截 200 字再脱敏，如果手机号
     * 正好卡在第 200 个字符上，截出来的是「1593535306」这种半截号码，
     * 脱敏正则匹配不到（长度不够），半截号码就这么漏出去了。先脱敏的话
     * 整段已经被换成 {@code [手机号已隐藏]}，截在哪儿都是安全的。</p>
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = PiiMasker.mask(text, piiProperties.getExtraTerms())
                .replaceAll("\\s+", " ").strip();
        return flat.length() <= PREVIEW_LIMIT ? flat : flat.substring(0, PREVIEW_LIMIT) + "…";
    }

    private static Long longOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String strOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }
}
