package com.example.kb.infrastructure.parser;

import com.example.kb.domain.po.KbChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 切块器：解析出来的 Document（页/段）→ KbChunk 列表。
 *
 * <p>策略（第 1 周，够用且可解释）：</p>
 * <ol>
 *   <li>token 用字符估算（中文 1 字 ≈ 1 token，其他 4 字符 ≈ 1 token，
 *       与 md 第 6.4 节的 {@code content.length() × 0.6} 同一套粗估）。</li>
 *   <li>按段落（\n）贪心装桶；段落本身超长时先收尾已攒的，再硬切该段落。</li>
 *   <li>相邻 chunk 之间带 overlap：把上一块尾部 N 个 token 折回字符数接到下一块开头，
 *       避免一句话正好被切断导致检索漏召。</li>
 * </ol>
 *
 * <p>⚠️ 为什么不用 Spring AI 的 TokenTextSplitter：它是英文 tokenizer 规则
 * （按空格分词），中文几乎分不出词，一整本书可能只剩几个块。第 2 周要精细切再换。</p>
 */
@Component
public class Chunker {

    private final int maxTokens;
    private final int overlapTokens;

    public Chunker(@Value("${rag.chunk.max-tokens:512}") int maxTokens,
                   @Value("${rag.chunk.overlap-tokens:80}") int overlapTokens) {
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlap 必须小于 maxTokens");
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * @param docId     kb_document.id
     * @param filename  文件名，写进 KbChunk.filename 供 metadata 冗余
     * @param documents DocumentParser 的输出
     * @return 按文档顺序排好的 chunk，chunkIndex 从 0 连续
     */
    public List<KbChunk> chunk(long docId, String filename, List<Document> documents) {
        List<KbChunk> out = new ArrayList<>();
        int index = 0;
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Integer page = intOrNull(doc.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER));
            String heading = strOrNull(doc.getMetadata().get(DocumentParser.META_HEADING_PATH));
            for (String piece : split(text)) {
                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(docId);
                chunk.setChunkIndex(index++);
                chunk.setFilename(filename);
                chunk.setPageNumber(page);
                chunk.setHeadingPath(heading);
                chunk.setContent(piece);
                chunk.setTokenCount(estimateTokens(piece));
                chunk.setContentHash(sha256(piece));
                out.add(chunk);
            }
        }
        return out;
    }

    /**
     * 单字符估算：max(CJK 字数, 非 CJK 字符数 / 4)。
     * 第 1 周只用于 512 上限粗判和前端展示，不做计费（硅基流动 bge-m3 不返回 usage）。
     */
    static int estimateTokens(String text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
            else {
                other++;
            }
        }
        return Math.max(cjk, (other + 3) / 4);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这说明 JDK 坏了，直接崩比静默错好
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    /** 段落贪心装桶 + 超长段硬切 + overlap */
    private List<String> split(String text) {
        List<String> paragraphs = List.of(text.split("\n+"));
        List<String> buckets = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (estimateTokens(p) > maxTokens) {
                // 段落本身就超长：先把已攒的收尾，再硬切这个段落
                if (!cur.isEmpty()) {
                    buckets.add(cur.toString());
                    cur.setLength(0);
                }
                hardSplit(p, buckets);
                continue;
            }
            if (!cur.isEmpty() && estimateTokens(cur + "\n" + p) > maxTokens) {
                buckets.add(cur.toString());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) {
                cur.append('\n');
            }
            cur.append(p);
        }
        if (!cur.isEmpty()) {
            buckets.add(cur.toString());
        }

        if (overlapTokens <= 0 || buckets.size() < 2) {
            return buckets;
        }
        return applyOverlap(buckets);
    }

    private void hardSplit(String text, List<String> out) {
        // 中文一字一 token，按 maxTokens 字符数直接切（宁可略超也别切碎词）
        int size = Math.max(1, maxTokens);
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(i + size, text.length())));
        }
    }

    /** 把上一块尾部按 token 折回字符数接到下一块开头 */
    private List<String> applyOverlap(List<String> buckets) {
        List<String> out = new ArrayList<>();
        String prevTail = "";
        for (String b : buckets) {
            out.add(prevTail.isEmpty() ? b : prevTail + "\n" + b);
            prevTail = tailOf(b, overlapTokens);
        }
        return out;
    }

    private static String tailOf(String text, int tokens) {
        int cut = text.length() - tokens;
        return cut <= 0 ? text : text.substring(cut);
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
