-- ============================================================
-- 增量迁移：个人站改造（在已有 rag 库上执行，可重复执行）
-- 只加不删：现有 kb_document / kb_chunk / vector_store 不动
-- ============================================================

-- ---------- 1. 管理员账号表（本站只有一个用户） ----------
CREATE TABLE IF NOT EXISTS kb_user (
    id             BIGSERIAL PRIMARY KEY,
    username       TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,                 -- BCrypt 散列，60 字符
    display_name   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

-- ---------- 2. 访客日志 ----------
CREATE TABLE IF NOT EXISTS kb_visit (
    id          BIGSERIAL PRIMARY KEY,
    ip          TEXT NOT NULL,
    path        TEXT,
    question    TEXT,
    country     TEXT,
    region      TEXT,
    city        TEXT,
    isp         TEXT,
    user_agent  TEXT,
    referer     TEXT,
    is_admin    BOOLEAN NOT NULL DEFAULT false,
    geo_status  TEXT NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_visit_created ON kb_visit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_visit_ip ON kb_visit (ip);
-- 后台只扫 PENDING，走部分索引
CREATE INDEX IF NOT EXISTS idx_kb_visit_pending ON kb_visit (id) WHERE geo_status = 'PENDING';

-- ---------- 3. 确认结果 ----------
SELECT 'kb_user' AS tbl, count(*) AS rows FROM kb_user
UNION ALL
SELECT 'kb_visit', count(*) FROM kb_visit;
