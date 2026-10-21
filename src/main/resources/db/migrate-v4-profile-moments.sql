-- =====================================================================
-- v4 增量迁移：关于我可编辑 + 日常动态 + 访客评论
--
-- ⚠️ 只加不删、可重复执行。已经跑过 v1/v2/v3 的库直接执行本文件即可。
-- ⚠️ 和 schema.sql 是两条路：schema.sql 只用于「全新安装」，
--    这个文件用于「已有数据的老库升级」。两者内容必须保持一致。
-- =====================================================================

-- ---------- 1. 站主资料（「关于我」等，可在后台在线编辑） ----------
--
-- ⚠️ 为什么是单行表（id 恒为 1 + CHECK 约束）而不是 key-value 表：
-- 「关于我」是一个整体（简介 + 段落 + 外链 + 标签），拆成 key-value 之后
-- 每次读取要查出所有行再拼，更新要逐行判断「有就改没有就插」，
-- 换来的「灵活」这个站根本用不上。单行表就是一处真相。
--
-- ⚠️ about / links / tags 用 JSONB 而不是三张子表：
-- 它们只是展示用的有序列表，不需要按段落查询、不需要单独索引，
-- 建三张子表会让「保存一次关于我」变成三条 DELETE + N 条 INSERT。
CREATE TABLE IF NOT EXISTS kb_profile (
    id          INTEGER PRIMARY KEY DEFAULT 1,
    owner       TEXT NOT NULL DEFAULT '',
    avatar_text TEXT NOT NULL DEFAULT '',
    tagline     TEXT NOT NULL DEFAULT '',
    intro       TEXT NOT NULL DEFAULT '',
    about       JSONB NOT NULL DEFAULT '[]'::jsonb,
    links       JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags        JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kb_profile_singleton CHECK (id = 1)
);

COMMENT ON TABLE kb_profile IS '站主资料单行表；为空时回落到 application.yml 的 rag.site.*';

-- ---------- 2. 日常动态 ----------
--
-- ⚠️ media_type 用文本枚举而不是文件后缀判断：
-- 前端要靠它决定渲染 <img> 还是 <video>，靠后缀猜会在
-- 「用户传了 .mov」时出问题，显式存下来最省事。
--
-- ⚠️ synced_photo_id 记录「这条动态同步到了照片墙的哪张图」：
-- 删动态时要连带删掉那条照片墙记录，否则照片墙里会留下
-- 一条没有出处的孤图。反过来删照片墙的记录不会动动态本体。
CREATE TABLE IF NOT EXISTS kb_moment (
    id              BIGSERIAL PRIMARY KEY,
    content         TEXT,
    media_url       TEXT,
    media_type      TEXT,
    media_width     INTEGER,
    media_height    INTEGER,
    media_size      BIGINT,
    synced_photo_id BIGINT,
    visible         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE kb_moment IS '站主日常动态：文字 / 图片 / 视频';
COMMENT ON COLUMN kb_moment.synced_photo_id IS '同步到照片墙后对应的 kb_photo.id，删动态时连带清理';
COMMENT ON COLUMN kb_moment.media_type IS 'IMAGE / VIDEO / 空（纯文字）';

CREATE INDEX IF NOT EXISTS idx_kb_moment_created ON kb_moment (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_moment_visible ON kb_moment (visible, created_at DESC);

-- ---------- 3. 访客评论（只有站主能看） ----------
--
-- ⚠️ FK ON DELETE CASCADE：删动态时评论跟着走。
--    这里可以放心用外键（和向量表不同），因为评论离开动态没有任何意义。
CREATE TABLE IF NOT EXISTS kb_moment_comment (
    id         BIGSERIAL PRIMARY KEY,
    moment_id  BIGINT NOT NULL REFERENCES kb_moment (id) ON DELETE CASCADE,
    nickname   TEXT NOT NULL DEFAULT '匿名访客',
    content    TEXT NOT NULL,
    ip         TEXT,
    is_read    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE kb_moment_comment IS '访客在动态下的评论；仅站主可见，前台只提示「已提交」';
COMMENT ON COLUMN kb_moment_comment.is_read IS '站主是否已读，用来在后台显示未读数';

CREATE INDEX IF NOT EXISTS idx_kb_moment_comment_moment ON kb_moment_comment (moment_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_moment_comment_unread ON kb_moment_comment (id) WHERE is_read = false;
