-- ============================================================
-- v5：头像支持图片
-- ============================================================
-- 背景：v4 的 kb_profile 只存了 avatar_text（一个字母），
-- 站主现在想放真照片。新增一列存图片 URL，avatar_text 保留作兜底：
-- 有图片就用图片，没图片就退回显示那个字，不会出现空白圆圈。
--
-- 只加不删，可重复执行（IF NOT EXISTS）。

ALTER TABLE kb_profile ADD COLUMN IF NOT EXISTS avatar_url TEXT NOT NULL DEFAULT '';

COMMENT ON COLUMN kb_profile.avatar_url IS '头像图片 URL；为空时前端回落到 avatar_text 显示首字母';
