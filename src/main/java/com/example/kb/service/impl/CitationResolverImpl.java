package com.example.kb.service.impl;

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
        return ref;
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

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
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
