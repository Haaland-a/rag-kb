-- ============================================================
-- v7：统一留言板 + 用户注册 + 设备限制
-- ============================================================
-- 背景：原来的 kb_moment_comment 只能挂在某条动态下面。现在需求变成
--   「用户不一定非要在我的动态下留言，也可以直接建一个留言板为我留言」
-- 所以要有一张能同时承载两种场景的表：moment_id 为 NULL 表示留言板。
--
-- 同时引入三条新能力：
--   1. 注册用户可以选「公开」或「悄悄话」（悄悄话仅站主可见）
--   2. 站主可以屏蔽某条留言（留言者仍能看到自己那条，知道被屏蔽了）
--   3. 游客不注册也能留言，但界面会明确提示「匿名留言不可修改、不可找回」
--
-- ⚠️ 为什么不给 kb_moment_comment 加列，而是新建 kb_comment 再迁移：
--   「评论」和「留言」在语义上已经分叉 —— 前者是动态的附属物（动态删了
--   评论就没意义），后者是独立的收件箱（留言板上的留言不属于任何动态）。
--   继续加列会让这表一半字段对另一半场景是 NULL。
--
-- 只加不删，可重复执行。

-- ---------- 先给 kb_user 加设备字段 ----------
-- ⚠️ 必须在建唯一索引之前：索引引用 device_id，列不存在会直接报错
ALTER TABLE kb_user ADD COLUMN IF NOT EXISTS device_id TEXT;
ALTER TABLE kb_user ADD COLUMN IF NOT EXISTS register_ip TEXT;
COMMENT ON COLUMN kb_user.device_id IS '注册时的浏览器设备指纹，唯一索引防止同设备多账号';
COMMENT ON COLUMN kb_user.register_ip IS '注册时的 IP，配合 device_id 做限制';

-- 一个设备只能有一个账号。
-- ⚠️ 部分唯一索引：只对 device_id 有值的行生效。早期手工建的账号
-- （比如站主自己）device_id 是 NULL，不受约束 —— 否则升级时会
-- 因为多个历史账号都为 NULL 而冲突（实际上 PG 认为多个 NULL 不相等，
-- 这里写 WHERE 是为了语义明确，也避免将来改成 DEFAULT '' 时炸掉）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_user_device
    ON kb_user (device_id) WHERE device_id IS NOT NULL;

-- ---------- 主表 ----------
CREATE TABLE IF NOT EXISTS kb_comment (
    id          BIGSERIAL PRIMARY KEY,

    -- NULL = 留言板（不挂在任何动态下）
    moment_id   BIGINT REFERENCES kb_moment (id) ON DELETE CASCADE,

    -- NULL = 游客（未注册）。有值 = kb_user.id
    user_id     BIGINT REFERENCES kb_user (id) ON DELETE SET NULL,

    nickname    TEXT NOT NULL DEFAULT '匿名访客',
    content     TEXT NOT NULL,
    ip          TEXT,

    -- 设备指纹。留言本身也记一份，这样同一设备换个昵称来刷留言能被串起来看
    device_id   TEXT,

    -- PUBLIC  对外可见（默认）
    -- PRIVATE 悄悄话，只有站主能看到（注册用户的选项）
    visibility  TEXT NOT NULL DEFAULT 'PUBLIC',

    -- VISIBLE 正常展示
    -- HIDDEN  站主屏蔽：其他人看不到，留言者自己仍能看到（知道自己被屏蔽了）
    status      TEXT NOT NULL DEFAULT 'VISIBLE',

    -- NULL = 顶层留言。有值 = 回复某条留言。
    -- 只做一层回复，不做无限楼中楼 —— 个人站的留言板不需要
    parent_id   BIGINT REFERENCES kb_comment (id) ON DELETE CASCADE,

    is_read     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE kb_comment IS '留言板 + 动态评论（moment_id 为空表示留言板）';
COMMENT ON COLUMN kb_comment.visibility IS 'PUBLIC=展示给所有人；PRIVATE=悄悄话，只有站主看得到';
COMMENT ON COLUMN kb_comment.status IS 'VISIBLE=正常；HIDDEN=站主屏蔽，别人看不到但留言者自己仍能看到';
COMMENT ON COLUMN kb_comment.device_id IS '浏览器指纹，用于设备级限制（同一设备不能注册多账号）';

CREATE INDEX IF NOT EXISTS idx_kb_comment_board
    ON kb_comment (created_at DESC) WHERE moment_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_kb_comment_moment
    ON kb_comment (moment_id, created_at DESC) WHERE moment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_kb_comment_unread
    ON kb_comment (id) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_kb_comment_user
    ON kb_comment (user_id) WHERE user_id IS NOT NULL;

-- ---------- 迁移旧数据 ----------
-- 把 kb_moment_comment 里已有的留言搬进 kb_comment。
-- ⚠️ 必须用 DO 块先判表存在：全新安装的环境压根没建过 kb_moment_comment，
-- 直接 INSERT ... FROM kb_moment_comment 会报 relation does not exist，
-- 导致整个迁移脚本中断（psql -v ON_ERROR_STOP=1 下后面全不跑）。
-- ⚠️ WHERE NOT EXISTS 保证可重复执行（重跑不会插重复）。
-- 以「同一动态 + 同一时间 + 同一内容」判定同一条，正常情况下足够唯一。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'kb_moment_comment'
    ) THEN
        INSERT INTO kb_comment (moment_id, nickname, content, ip, is_read, created_at)
        SELECT c.moment_id, c.nickname, c.content, c.ip, c.is_read, c.created_at
        FROM kb_moment_comment c
        WHERE NOT EXISTS (
            SELECT 1 FROM kb_comment k
            WHERE k.moment_id = c.moment_id
              AND k.created_at = c.created_at
              AND k.content = c.content
        );
        RAISE NOTICE '已从 kb_moment_comment 迁移历史留言';
    ELSE
        RAISE NOTICE 'kb_moment_comment 不存在（全新安装），跳过历史数据迁移';
    END IF;
END $$;

-- 旧表保留不删：万一迁移有遗漏还能回头查。
-- 确认新表数据完整后可以手工执行：DROP TABLE kb_moment_comment;
