package com.example.kb.infrastructure.parser;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析器：PDF / MD / TXT → 按页/按段的 Document 列表。
 *
 * <p>为什么自己写而不是全交给 Spring AI reader：</p>
 * <ul>
 *   <li>PDF 走 {@link PagePdfDocumentReader}（它按 pagesPerDocument 切页，
 *       metadata 只给 page_number / end_page_number / file_name 三个 public key）。</li>
 *   <li>MD / TXT 没有对应 reader，且 MD 的标题层级是 headingPath 的数据源，必须自己扫。</li>
 *   <li>统一成「页/段 → Document」这一个中间形态，下游 {@link Chunker} 只处理一种输入。</li>
 * </ul>
 *
 * <p>⚠️ MD 的 headingPath 由本类扫标题得到，PDF 的 headingPath 暂为空
 * （第 1 周 PDF 按页切，拿不到标题层级；ParagraphPdfDocumentReader 的 metadata key
 * 是 private 常量，硬编码字符串容易漂移，所以第 1 周不用它）。</p>
 */
@Service
public class DocumentParser {

    /** 我们自己的 metadata key：标题路径，如「第二章 > 2.1 配置」。Spring AI reader 不产这个 */
    public static final String META_HEADING_PATH = "heading_path";

    /** PDF：一页一个 Document，metadata 的 page_number 就是它 */
    public List<Document> parsePdf(Path file) throws IOException {
        Resource resource = new FileSystemResource(file);
        return new PagePdfDocumentReader(resource).get();
    }

    /**
     * MD：按空行分段，每段一个 Document；标题行进 headingPath 栈。
     *
     * <p>metadata 里的 heading_path 用我们自己的常量 {@link #META_HEADING_PATH}
     * （Spring AI reader 不产这个 key），切块时读它。</p>
     */
    public List<Document> parseMarkdown(Path file, String filename) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Document> out = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                flushBody(out, body, headingStack, filename);
                int level = countHashes(line);
                String title = line.substring(level).strip();
                while (!headingStack.isEmpty() && headingStack.size() >= level) {
                    headingStack.pollLast();
                }
                headingStack.addLast(title);
                continue;
            }
            // 分隔线也当作段落边界，避免把 --- 吃进正文
            if (line.matches("^-{3,}$|\\*{3,}$|_{3,}$")) {
                flushBody(out, body, headingStack, filename);
                continue;
            }
            body.append(line).append('\n');
        }
        flushBody(out, body, headingStack, filename);
        return out;
    }

    /** TXT：按空行分段，metadata 只有 file_name */
    public List<Document> parseTxt(Path file, String filename) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Document> out = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        for (String raw : lines) {
            if (raw.strip().isEmpty()) {
                flushPlain(out, body, filename);
                continue;
            }
            body.append(raw.strip()).append('\n');
        }
        flushPlain(out, body, filename);
        return out;
    }

    /**
     * 按扩展名分发。返回的 Document 上保证带 {@code file_name} metadata
     * （PDF reader 自带；MD/TXT 由本类补），否则切块时 sources 拿不到文件名。
     *
     * <p>MD/TXT 没有页码概念，统一补 page_number=1，下游不用判 null。</p>
     */
    public List<Document> parse(Path file, String fileType) throws IOException {
        String filename = file.getFileName().toString();
        List<Document> docs = switch (fileType) {
            case "pdf" -> parsePdf(file);
            case "md" -> parseMarkdown(file, filename);
            case "txt" -> parseTxt(file, filename);
            default -> throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        };
        for (Document doc : docs) {
            doc.getMetadata().putIfAbsent(PagePdfDocumentReader.METADATA_FILE_NAME, filename);
            doc.getMetadata().putIfAbsent(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER, 1);
        }
        return docs;
    }

    private static int countHashes(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') {
            n++;
        }
        return n;
    }

    private void flushBody(List<Document> out, StringBuilder body,
                           Deque<String> headingStack, String filename) {
        if (body.isEmpty()) {
            return;
        }
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(PagePdfDocumentReader.METADATA_FILE_NAME, filename);
        meta.put(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER, 1);
        meta.put(META_HEADING_PATH, String.join(" > ", headingStack));
        out.add(Document.builder().text(text).metadata(meta).build());
    }

    private void flushPlain(List<Document> out, StringBuilder body, String filename) {
        if (body.isEmpty()) {
            return;
        }
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(PagePdfDocumentReader.METADATA_FILE_NAME, filename);
        meta.put(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER, 1);
        out.add(Document.builder().text(text).metadata(meta).build());
    }
}
