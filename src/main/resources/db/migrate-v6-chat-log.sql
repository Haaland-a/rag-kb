-- ============================================================
-- v6：问答记录
-- ============================================================
-- 背景：现在 kb_visit 只存了「问题原文」，回答内容和引用了哪些资料都没留。
-- 站主想知道「别人问了我什么、我答复了什么」，需要把整段对话存下来。
--
-- ⚠️ 为什么新建一张表而不是给 kb_visit 加列：
--   1. kb_visit 记录所有访问（主页、文档页、爬虫），问答只是其中一小部分。
--      给每次访问都加 answer/sources 两个大字段，表体积会被页脚访问撑大三倍
--   2. 问答记录要能单独分页、单独筛（只看有回答的）、单独删
--   3. 语义不同：kb_visit 是「访问日志」，kb_chat 是「对话记录」
--
-- ⚠️ 归属地不在这张表里重复存：同一 IP 的归属地 kb_visit 已经有了，
--   查询时用 LATERAL 子查询取最新那条即可。存两份就要维护两处补全逻辑。
--
-- 只加不删，可重复执行（IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS kb_chat (
    id           BIGSERIAL PRIMARY KEY,
    ip           TEXT,
    question     TEXT,
    answer       TEXT,
    sources      JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms   INTEGER,
    answer_chars INTEGER,
    complete     BOOLEAN NOT NULL DEFAULT TRUE,
    is_admin     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE kb_chat IS '问答记录：谁问了什么、答了什么、引用了哪些资料';
COMMENT ON COLUMN kb_chat.sources IS '引用来源数组，元素形如 {chunkId,documentId,filename,pageNumber,headingPath,score,preview}';
COMMENT ON COLUMN kb_chat.complete IS 'false 表示访客中途关掉了页面（回答没生成完）。这类记录仍保留，因为「问了什么」本身有价值';

CREATE INDEX IF NOT EXISTS idx_kb_chat_created ON kb_chat (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_chat_ip ON kb_chat (ip);
