-- ============================================================
-- 个人站内容表：照片墙 / 项目展示 / 简历
--
-- 设计要点：
-- 1. 三张表都用 sort_order 让站主自己排顺序（默认按创建时间倒序）
-- 2. 图片/文件的实际内容在 OSS 或本地磁盘，这里只存 URL + 元信息
-- 3. media_url 存完整 URL（本地是 /media/xxx，OSS 是 https://...）
--    —— 不存 key，因为换存储后端时 URL 形态会变，存 key 反而要额外拼装
-- 4. title / caption 允许为空：照片可以只配一句话，也可以什么都不配
-- ============================================================

-- 照片墙：主页展示的生活照 / 风景照
CREATE TABLE IF NOT EXISTS kb_photo (
    id BIGSERIAL PRIMARY KEY,
    -- 图片地址：本地 /media/photos/xxx.jpg 或 https://bucket.oss/...
    url TEXT NOT NULL,
    -- 一句话说明，可为空
    caption TEXT,
    -- 排序：越小越靠前，相同则按 id 倒序
    sort_order INT NOT NULL DEFAULT 0,
    -- 是否在主页显示（可以不删除但暂时隐藏）
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    -- 图片尺寸，前端用来占位避免布局跳动（上传时读一次，读不到就留 null）
    width INT,
    height INT,
    -- 文件大小，做配额统计用
    file_size BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_photo_visible_sort
    ON kb_photo (visible, sort_order, id DESC);
CREATE INDEX IF NOT EXISTS idx_kb_photo_created
    ON kb_photo (created_at DESC);

-- 项目展示：卡片式，每张有截图 + 标题 + 说明 + 技术栈 + 链接
CREATE TABLE IF NOT EXISTS kb_project (
    id BIGSERIAL PRIMARY KEY,
    -- 项目名
    title TEXT NOT NULL,
    -- 一句话说明
    description TEXT,
    -- 截图地址，可为空（有的项目不想放图）
    cover_url TEXT,
    -- 技术栈标签，逗号分隔存着（只有站主用，不必拆表）
    tech TEXT,
    -- 外链：GitHub 仓库 / 在线演示
    repo_url TEXT,
    demo_url TEXT,
    -- 排序：越小越靠前
    sort_order INT NOT NULL DEFAULT 0,
    -- 是否在主页显示
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_project_visible_sort
    ON kb_project (visible, sort_order, id DESC);

-- 简历 / 作品集下载
CREATE TABLE IF NOT EXISTS kb_download (
    id BIGSERIAL PRIMARY KEY,
    -- 展示名，如「Java 后端简历」「作品集 2026」
    title TEXT NOT NULL,
    -- 文件地址
    url TEXT NOT NULL,
    -- 文件类型：pdf / docx / zip，前端显示图标用
    file_type TEXT,
    file_size BIGINT,
    -- 下载次数，站主会想知道有没有人下
    download_count BIGINT NOT NULL DEFAULT 0,
    -- 排序：越小越靠前
    sort_order INT NOT NULL DEFAULT 0,
    -- 是否在主页显示
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_download_visible_sort
    ON kb_download (visible, sort_order, id DESC);

COMMENT ON TABLE kb_photo IS '主页照片墙的生活照/风景照';
COMMENT ON TABLE kb_project IS '主页项目展示卡片';
COMMENT ON TABLE kb_download IS '简历/作品集下载项';
