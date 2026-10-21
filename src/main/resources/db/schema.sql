-- ============================================================
-- RAG 知识库完整建表脚本（全新安装用）
-- 增量升级见 migrate-v2-personal.sql（kb_user/kb_visit）
--          migrate-v3-media.sql（kb_photo/kb_project/kb_download）
--          migrate-v4-profile-moments.sql（kb_profile/kb_moment）
--          migrate-v6-chat-log.sql（kb_chat）
--          migrate-v7-comments-board.sql（kb_comment + kb_user 设备字段）
--          migrate-v5-avatar.sql（kb_profile.avatar_url）
--          migrate-v6-chat-log.sql（kb_chat 问答记录）
-- ============================================================

-- ---------- 扩展 ----------
CREATE EXTENSION IF NOT EXISTS vector;      -- pgvector，向量列类型
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- uuid_generate_v4()，向量表主键默认值
CREATE EXTENSION IF NOT EXISTS hstore;      -- Spring AI PgVectorStore 建表时代码里要
CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- 第 2 周关键词检索（RRF 的一路）

-- ---------- 文档表 ----------
CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGSERIAL PRIMARY KEY,
    filename      TEXT NOT NULL,
    content_hash  CHAR(64) NOT NULL,          -- 整篇 SHA-256，用于上传查重
    file_type     TEXT NOT NULL,              -- pdf / md / txt
    file_size     BIGINT NOT NULL,
    page_count    INTEGER,
    chunk_count   INTEGER NOT NULL DEFAULT 0,
    status        TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/INDEXING/READY/FAILED
    error_msg     TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_document_content_hash ON kb_document (content_hash);
CREATE INDEX IF NOT EXISTS idx_kb_document_status ON kb_document (status);

-- ---------- 切块表 ----------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    chunk_index  INTEGER NOT NULL,
    page_number  INTEGER,
    heading_path TEXT,
    content      TEXT NOT NULL,
    token_count  INTEGER NOT NULL DEFAULT 0,
    content_hash CHAR(64) NOT NULL,
    vector_id    CHAR(36),                    -- vector_store.id（UUID 字符串）
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kb_chunk_document_id_chunk_index_key UNIQUE (document_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_document ON kb_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_vector ON kb_chunk (vector_id);
-- 第 2 周关键词检索用：pg_trgm 对中文子串匹配有效
CREATE INDEX IF NOT EXISTS idx_kb_chunk_content_trgm
    ON kb_chunk USING gin (content gin_trgm_ops);

-- ---------- 向量表 ----------
-- ⚠️ 这张表由 Spring AI 的 PgVectorStore 自动创建（initializeSchema=true），
--    不需要手工建。这里只是注释说明结构：
--    CREATE TABLE vector_store (
--        id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
--        content text,
--        metadata json,
--        embedding vector(1024)     -- 维度跟 embedding 模型走，bge-m3/百炼都是 1024
--    );
--    CREATE INDEX ... USING hnsw (embedding vector_cosine_ops);
--
-- ⚠️ 双轨设计：vector_store 管向量检索，kb_chunk 管结构化查询（分页、按文档筛）。
--    两者靠 metadata->>'chunkId' 关联（写向量时塞进 metadata 的 chunkId）。
--    为什么不放同一张表：pgvector 的列只服务于相似度查询，翻页/统计走
--    B-tree 索引的 kb_chunk 更快；而且 Spring AI 建表语句是它自己管的。

-- ---------- 用户账号表 ----------
-- 站主由 AdminInitializer 保证存在（用户名匹配 rag.admin.username）。
-- 除此之外允许访客自助注册成普通用户 —— 但注册用户<b>不是管理员</b>：
-- 管理员身份靠路径白名单 + requireAdmin 判断，不靠这张表的任何标志位。
--
-- device_id：注册时的浏览器指纹，配合部分唯一索引 uk_kb_user_device
--   实现「同一设备不能注册多账号」。早期手工建的账号该列为 NULL，不受约束。
-- register_ip：配合 device_id 做第二道软限制（同 IP 24 小时内限 2 个）。
CREATE TABLE IF NOT EXISTS kb_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,              -- BCrypt 散列，60 字符
    display_name  TEXT,
    device_id     TEXT,                       -- 浏览器指纹，防同设备多账号
    register_ip   TEXT,                       -- 注册来源 IP，配合上面做软限制
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- 一个设备只能有一个账号。
-- ⚠️ 部分唯一索引（WHERE device_id IS NOT NULL）：手工建的账号
-- device_id 为 NULL，不受约束，否则升级时多行 NULL 会互相冲突。
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_user_device
    ON kb_user (device_id) WHERE device_id IS NOT NULL;

-- ---------- 访客日志表 ----------
-- 设计要点：
--   1. 每个访问请求先同步插入一行（只写 IP + 时间 + 路径，几十字节），
--      归属地由后台异步补写 —— 查归属地要调外部 API，快则 100ms 慢则超时，
--      放同步链路会把主页拖死。
--   2. geo_status 显式标记补写状态，失败的行可以重试，不用靠猜。
--   3. 不建外键、不存密码类信息，这是日志表不是业务表。
CREATE TABLE IF NOT EXISTS kb_visit (
    id          BIGSERIAL PRIMARY KEY,
    ip          TEXT NOT NULL,
    path        TEXT,
    question    TEXT,                          -- 若这次是问答请求，记录问题原文
    country     TEXT,                          -- 以下五列由异步任务回填
    region      TEXT,
    city        TEXT,
    isp         TEXT,
    user_agent  TEXT,
    referer     TEXT,
    is_admin    BOOLEAN NOT NULL DEFAULT false,
    geo_status  TEXT NOT NULL DEFAULT 'PENDING', -- PENDING / DONE / FAILED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_visit_created ON kb_visit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_visit_ip ON kb_visit (ip);
CREATE INDEX IF NOT EXISTS idx_kb_visit_pending ON kb_visit (id) WHERE geo_status = 'PENDING';

-- ---------- 个人内容：照片墙 ----------
CREATE TABLE IF NOT EXISTS kb_photo (
    id          BIGSERIAL PRIMARY KEY,
    url         TEXT NOT NULL,                 -- /media/xxx 或 https://bucket.oss/xxx
    caption     TEXT,
    sort_order  INT NOT NULL DEFAULT 0,
    visible     BOOLEAN NOT NULL DEFAULT TRUE,
    width       INT,
    height      INT,
    file_size   BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_photo_visible_sort ON kb_photo (visible, sort_order, id DESC);
CREATE INDEX IF NOT EXISTS idx_kb_photo_created ON kb_photo (created_at DESC);

-- ---------- 个人内容：项目展示 ----------
CREATE TABLE IF NOT EXISTS kb_project (
    id          BIGSERIAL PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT,
    cover_url   TEXT,
    tech        TEXT,                          -- 逗号分隔，只有站主用不必拆表
    repo_url    TEXT,
    demo_url    TEXT,
    sort_order  INT NOT NULL DEFAULT 0,
    visible     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_project_visible_sort ON kb_project (visible, sort_order, id DESC);

-- ---------- 个人内容：简历/作品集下载 ----------
CREATE TABLE IF NOT EXISTS kb_download (
    id             BIGSERIAL PRIMARY KEY,
    title          TEXT NOT NULL,
    url            TEXT NOT NULL,
    file_type      TEXT,                       -- pdf / docx / zip
    file_size      BIGINT,
    download_count BIGINT NOT NULL DEFAULT 0,
    sort_order     INT NOT NULL DEFAULT 0,
    visible        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_download_visible_sort ON kb_download (visible, sort_order, id DESC);

-- ---------- 站主资料（「关于我」，后台可在线编辑） ----------
-- 单行表：id 恒为 1 并由 CHECK 约束锁死。整行读写一处真相，
-- 不建三张子表（about/links/tags 存 JSONB，它们只是展示用的有序列表）。
-- 表为空时回落到 application.yml 的 rag.site.*（fromConfig=true）。
-- avatar_url 有值时优先显示图片，为空退回 avatar_text 那个字。
CREATE TABLE IF NOT EXISTS kb_profile (
    id          INTEGER PRIMARY KEY DEFAULT 1,
    owner       TEXT NOT NULL DEFAULT '',
    avatar_text TEXT NOT NULL DEFAULT '',
    avatar_url  TEXT NOT NULL DEFAULT '',
    tagline     TEXT NOT NULL DEFAULT '',
    intro       TEXT NOT NULL DEFAULT '',
    about       JSONB NOT NULL DEFAULT '[]'::jsonb,
    links       JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags        JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kb_profile_singleton CHECK (id = 1)
);

-- ---------- 日常动态（文字 / 图片 / 视频） ----------
-- media_type 显式存 IMAGE/VIDEO 而不是靠后缀猜：前端要靠它决定渲染 <img> 还是 <video>。
-- synced_photo_id 记录同步到照片墙的哪张图，删动态时连带清理那条孤图。
CREATE TABLE IF NOT EXISTS kb_moment (
    id              BIGSERIAL PRIMARY KEY,
    content         TEXT,
    media_url       TEXT,
    media_type      TEXT,
    media_width     INTEGER,
    media_height    INTEGER,
    media_size      BIGINT,
    synced_photo_id BIGINT,
    visible         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_moment_created ON kb_moment (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_moment_visible ON kb_moment (visible, created_at DESC);

-- ---------- 留言板 + 动态评论（统一一张表） ----------
-- 为什么不是两张表：留言板上的留言和动态下的评论字段 90% 重合
-- （内容、作者、可见性、状态、回复关系、已读），拆开要维护两套 Repository
-- 和两套 Controller。代价是每个查询都得带上 moment_id IS NULL / IS NOT NULL。
-- moment_id 为 NULL 时表示留言板。
--
-- ⚠️ 旧表 kb_moment_comment 已废弃，由 migrate-v7-comments-board.sql 迁移到本表。
-- 新装环境不需要建 kb_moment_comment。
CREATE TABLE IF NOT EXISTS kb_comment (
    id         BIGSERIAL PRIMARY KEY,
    -- NULL = 留言板
    moment_id  BIGINT REFERENCES kb_moment (id) ON DELETE CASCADE,
    -- NULL = 游客（未注册）
    user_id    BIGINT REFERENCES kb_user (id) ON DELETE SET NULL,
    nickname   TEXT NOT NULL DEFAULT '匿名访客',
    content    TEXT NOT NULL,
    ip         TEXT,
    device_id  TEXT,
    -- PUBLIC 展示；PRIVATE 悄悄话仅站主可见
    visibility TEXT NOT NULL DEFAULT 'PUBLIC',
    -- VISIBLE 正常；HIDDEN 站主屏蔽（留言者自己仍能看到）
    status     TEXT NOT NULL DEFAULT 'VISIBLE',
    -- NULL = 顶层；非 NULL = 回复某条留言（只做一层）
    parent_id  BIGINT REFERENCES kb_comment (id) ON DELETE CASCADE,
    is_read    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_comment_board
    ON kb_comment (created_at DESC) WHERE moment_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_kb_comment_moment
    ON kb_comment (moment_id, created_at DESC) WHERE moment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_kb_comment_unread
    ON kb_comment (id) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_kb_comment_user
    ON kb_comment (user_id) WHERE user_id IS NOT NULL;

-- ---------- 问答记录 ----------
-- 和 kb_visit 分开的理由：kb_visit 记录所有访问（主页 / 文档页 / 爬虫），
-- 问答只是其中一小部分。给每次访问都挂 answer/sources 两个大字段，
-- 表体积会被页脚访问撑大三倍。
-- sources 存 JSONB 数组；归属地不在这里重复存，查询时从 kb_visit 取最新那条。
-- complete=false 表示访客中途关页面，回答没生成完 —— 这类记录仍保留，
-- 因为「问了什么」本身有价值，而且能看出访客在哪儿失去耐心。
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
CREATE INDEX IF NOT EXISTS idx_kb_chat_created ON kb_chat (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_chat_ip ON kb_chat (ip);

-- ============================================================
-- cube 兜底（仅当库里没有 pgvector 扩展时用，见 README 路径 C）
-- ============================================================
-- ⚠️ pgvector 官方不发 Windows 二进制，Windows 本机部署只能走 PG 自带的 cube：
--    CREATE EXTENSION IF NOT EXISTS cube;
--    CREATE TABLE vector_store (
--        id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
--        content text, metadata json, embedding cube
--    );
--    并把 AiConfig 改为 .initializeSchema(false)，相似度 SQL 换成
--    cube_distance(embedding, ?::cube)。
-- ⚠️ 语义从 cosine 变 L2：score 换算和 similarity-threshold=0.35 都要重标定。
