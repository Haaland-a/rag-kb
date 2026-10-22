package com.example.kb.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 问答请求。
 *
 * <p>documentIds 为空 = 全库检索；非空 = 只在选中的文档里找
 * （前端 ChatView 左栏多选框的值）。</p>
 */
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题最长 2000 字")
    private String question;

    private List<Long> documentIds;

    /** 可选：不传则用 rag.retrieval.top-k 默认值 */
    private Integer topK;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<Long> getDocumentIds() {
        return documentIds;
    }

    public void setDocumentIds(List<Long> documentIds) {
        this.documentIds = documentIds;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
