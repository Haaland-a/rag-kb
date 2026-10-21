package com.example.kb.infrastructure.vectorstore;

import com.example.kb.domain.KbChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：metadata 里不能有 null value。
 *
 * <p>线上真实故障：上传一份 PDF，索引失败，错误是
 * {@code metadata cannot have null values}。根因是 PDF 走 PagePdfDocumentReader
 * 拿不到标题层级，chunk.headingPath 恒为 null，而 toDocument 无条件 put 进
 * metadata；Spring AI 的 Document 构造器里有
 * {@code Assert.noNullElements(metadata.values(), "metadata cannot have null values")}，
 * 一个 null 就整篇抛异常。</p>
 *
 * <p>这个测试不需要 Spring 上下文，也不需要数据库和 embedding 模型 ——
 * 直接测 toDocument 这个纯函数，跑起来是毫秒级。</p>
 */
class VectorStoreGatewayMetadataTest {

    /** toDocument 不碰 vectorStore/配置，用反射构造一个最小实例即可 */
    private static VectorStoreGateway gateway() throws Exception {
        Constructor<VectorStoreGateway> c = VectorStoreGateway.class.getDeclaredConstructor(
                org.springframework.ai.vectorstore.VectorStore.class, String.class, int.class, double.class);
        c.setAccessible(true);
        return c.newInstance(null, "vector_store", 5, 0.5);
    }

    private static KbChunk chunk(String headingPath, Integer pageNumber) {
        KbChunk c = new KbChunk();
        c.setId(1L);
        c.setDocumentId(10L);
        c.setFilename("简历.pdf");
        c.setPageNumber(pageNumber);
        c.setHeadingPath(headingPath);
        c.setContent("正文内容");
        return c;
    }

    @Test
    @DisplayName("PDF 场景：headingPath 为 null 时不能抛异常（线上故障复现）")
    void nullHeadingPathDoesNotThrow() throws Exception {
        Document doc = gateway().toDocument(chunk(null, 1));
        assertNotNull(doc);
        // 该放的还是要放
        assertEquals(1L, doc.getMetadata().get(VectorStoreGateway.META_CHUNK_ID));
        assertEquals("简历.pdf", doc.getMetadata().get(VectorStoreGateway.META_FILENAME));
        assertEquals(1, doc.getMetadata().get(VectorStoreGateway.META_PAGE_NUMBER));
        // null 的那个直接不放 key，而不是放个 null 进去
        assertFalse(doc.getMetadata().containsKey(VectorStoreGateway.META_HEADING_PATH),
                "null 的 headingPath 不应该出现在 metadata 里");
    }

    @Test
    @DisplayName("TXT 场景：pageNumber 与 headingPath 都为 null 时也不能抛")
    void nullPageNumberAndHeadingDoesNotThrow() throws Exception {
        Document doc = gateway().toDocument(chunk(null, null));
        assertNotNull(doc);
        assertEquals(1L, doc.getMetadata().get(VectorStoreGateway.META_CHUNK_ID));
        assertFalse(doc.getMetadata().containsKey(VectorStoreGateway.META_PAGE_NUMBER));
        assertFalse(doc.getMetadata().containsKey(VectorStoreGateway.META_HEADING_PATH));
    }

    @Test
    @DisplayName("MD 场景：有 headingPath 时必须保留")
    void nonNullHeadingPathIsKept() throws Exception {
        Document doc = gateway().toDocument(chunk("第二章 > 2.1 配置", 3));
        assertEquals("第二章 > 2.1 配置", doc.getMetadata().get(VectorStoreGateway.META_HEADING_PATH));
        assertEquals(3, doc.getMetadata().get(VectorStoreGateway.META_PAGE_NUMBER));
    }

    @Test
    @DisplayName("metadata 里任何 value 都不为 null")
    void noNullValuesAtAll() throws Exception {
        Document doc = gateway().toDocument(chunk(null, null));
        assertTrue(doc.getMetadata().values().stream().noneMatch(java.util.Objects::isNull));
    }
}
