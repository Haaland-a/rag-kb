# 个人网站 + 知识库问答

一个「个人站 + 悬浮问答窗 + 留言板」的单体应用：访客打开首页看到的是站主的个人主页，
右下角有个小气泡，点开就能向我上传的资料提问；站主登录后管理文档、查看访客记录、发日常动态。

访客可以匿名留言，也可以注册一个账号（一个浏览器只能注册一个）—— 注册后留言可以选
「公开」还是「只给站主看的悄悄话」，并且能编辑/删除自己发过的留言。

![首页](docs/screenshots/home.png)

**技术栈**：Spring Boot 3.5 + Spring AI 1.1 + PostgreSQL 16 + pgvector + Vue 3 + Element Plus。
**规模**：单机 2 核 2G / 183 个文件 / 无前端独立服务 / 无 Redis / 无消息队列。

**部署形态**：一台 2 核 2G 的云服务器，JDK 17 + PostgreSQL 16（pgvector）+ nginx 反代，
全部跑在一台机器上，实测常驻内存约 900MB。
裸 IP 没有域名，所以用的是 Let's Encrypt 的 **IP 证书**（160 小时有效期，cron 自动续期）。
HTTP 80 自动 301 到 HTTPS。细节见 [deploy/https/README.md](deploy/https/README.md)。

> 这个仓库是一个**可跑起来的完整项目**，不是脚手架：13 张表的 schema、6 个增量迁移脚本、
> 一整套前后端、部署到一台 2C2G 云服务器的脚本都在里面。
> 想直接跑起来看效果，跳到 [三、快速开始](#三快速开始)；想先看设计取舍，看 [六、关键设计取舍](#六关键设计取舍)。

---

## 一、这个站是什么

**先是一个个人网站，其次才是 RAG。** 首页不是聊天框 —— 首屏是「你好，我是 XXX」的
自我介绍页，问答只是页面右下角一个可以点开的小窗口。

| 角色 | 能做什么 | 需要登录 |
| --- | --- | --- |
| **游客**（任何人） | 看个人主页（头像/简介/标签/外链/日常动态/照片墙/项目/下载）、点右下角小气泡提问、在留言板和动态下匿名留言 | 不需要 |
| **注册用户** | 游客的全部能力，外加：留言可选「公开」或「悄悄话」，可以编辑/删除自己的留言 | 需要（`/register` 注册） |
| **站主**（只有一个账号） | 上传/删除文档、重建索引、看访客日志（IP/地点/时间/问了什么）、看问答记录、管理照片墙/项目/简历、编辑「关于我」、发日常动态、管理全部留言（可屏蔽） | 需要 |

访客不需要注册也能留言。系统记录其 **IP + 归属地 + 访问时间 + 问的问题**，站主在后台可见。
按 IP 限流，默认每小时 20 次问答，防止单个访客刷光模型免费额度。站主不受限。

### 三种身份与留言可见性

留言功能有三层身份，规则刻意设计成不对称：

| 身份 | 留言可见范围 | 能否编辑/删除 | 提交时看到的提示 |
| --- | --- | --- | --- |
| **游客** | 公开留言，所有人能看到 | **不能**（没有稳定身份，无法证明归属） | 「发布后不能修改、不能删除，你也不会再找回它」 |
| **注册用户（公开）** | 所有人能看到 | **能**，随时改/删自己的 | 「留言已发布，其他人可以看到」 |
| **注册用户（悄悄话）** | 只有站主能看到 | **能** | 「只有站主能看到，其他人（包括留言列表里）都看不到」 |

> ⚠️ 为什么要主动提示：如果不说，访客会以为自己发的是公开评论，第二天回来看发现没了，
> 会以为系统坏了。诚实地告诉他规则，这个功能才站得住。
> 提示文案统一由**后端返回**，前端只负责显示 —— 三种身份三种说法只维护一处。

### 注册门槛：同一设备不能注册多账号

三层限制，都不是硬墙但足够挡普通滥用：

1. **设备指纹唯一索引**（`uk_kb_user_device`）—— 数据库层硬约束。前端用
   `src/utils/device.js` 算一个稳定串（UA / 语言 / 屏幕 / 时区 / 核数，FNV-1a 哈希），
   存 localStorage。清缓存会换指纹，能绕过。
2. **同 IP 24 小时限 2 个** —— 挡「清缓存换指纹重来」。动态 IP / 校园网 NAT 会误伤，所以阈值宽松。
3. **用户名唯一** —— 数据库约束。

> ⚠️ **站主用户名（`rag.admin.username`，默认 `demo`）禁止注册**。否则有人抢注这个名字，虽然登录不进去（密码不同），
> 但界面上会显示成两个同名账号，能骗到访客。
>
> ⚠️ 真正的兜底是「注册了也只能留言」—— 普通用户没有站主权限，
> 所有 `/api/documents`、`/api/visits`、`/api/comments`（管理端）接口都会 403。
> 管理权限靠 `AuthInterceptor` 的路径白名单 + 各端点的 `requireAdmin()`，
> **不是数据库里的 is_admin 标志位**（标志位一条 UPDATE 就能提权）。

### 页面结构

| 路由 | 页面 | 谁看 |
| --- | --- | --- |
| `/` | 个人主页（首屏 / 关于我 / 日常 / 项目 / 照片墙 / 我的知识库 / 页脚）+ 悬浮问答窗 | 所有人 |
| `/moments` | 日常动态流，可以在每条下面留言（公开留言可见） | 所有人 |
| `/board` | 留言板：独立区域，不挂在某条动态下 | 所有人 |
| `/login` | 登录 | 所有人 |
| `/register` | 注册普通用户（一个浏览器只能注册一个） | 游客 |
| `/admin/dashboard` | 后台概览：数据四卡 + 最近资料 + 快捷操作 + 上传弹窗 | 站主 |
| `/admin/documents` | 文档管理：上传、删除、重建索引、看切块 | 站主 |
| `/admin/documents/:id` | 单篇文档的切块详情（分页） | 站主 |
| `/admin/media` | 我的内容：照片墙 / 项目展示 / 简历下载三个标签页 | 站主 |
| `/admin/moments` | 日常动态：发文字/图片/视频，可同步到照片墙，看某条下的留言 | 站主 |
| `/admin/comments` | 留言管理：含悄悄话与已屏蔽，可筛选未读/类型，可屏蔽 | 站主 |
| `/admin/profile` | 关于我：在线编辑简介/段落/外链/标签/头像 | 站主 |
| `/admin/chats` | 问答记录：访客问了什么、回答了什么、引用了几条 | 站主 |
| `/admin/visits` | 访客记录：按 IP 聚合 / 原始流水 | 站主 |

> **登录入口在哪**：不需要登录也能看完整站（主页、日常、留言板、问答浮窗都开放），
> 所以进站不会弹登录框、也不会被重定向到 `/login`。**但右上角始终有一个「登录」小链接**，
> 要进后台的站主直接点它，不用手动敲地址。三个面向游客的页面（主页 / 日常 / 留言板）
> 顶栏都留了这个入口，位置统一在最右。
>
> 为什么不做成「首页压根没有登录入口」：试过，结果是站主每次进站都得记地址栏。
> 「不强制登录」和「藏起入口」是两件事，只做前者。

### 日常动态与访客留言

**「日常」是本站唯一会天天更新的区域。** 站主在 `/admin/moments` 发一条，可以是纯文字、
一张图或一段视频；发布时可以勾选「同时发到照片墙」，站主不用为了放一张照片传两遍。

访客在 `/moments` 页或主页「最近的日常」区块下能看到动态，并可以在每条下面留言；
也可以去独立的 `/board` 留言板留言（不挂在某条动态下）。

留言的可见性按身份分（详见上面「三种身份与留言可见性」）：

| 谁能看到什么 | 说明 |
| --- | --- |
| 站主 | 全部留言（昵称 / 内容 / IP / 时间 / 是否悄悄话），在 `/admin/comments` 按未读/类型筛选 |
| 公开留言 | 所有人可见，包括游客；注册用户的公开留言作者自己可以改/删 |
| 悄悄话 | **只有站主能看到**。发的人自己再回来也只在「我的留言」里看到，不进公开列表 |
| 被站主屏蔽的留言 | 其他人看不到；**留言者本人仍然能看到**，并标着「已被站主隐藏」 |

> ⚠️ **屏蔽为什么不是彻底隐藏**：如果留言者完全看不到自己那条，他会以为系统出 bug、
> 反复刷新、再发一遍。让他看到「已被隐藏」比让他猜要好。
>
> ⚠️ **副作用**：游客没有 `user_id`，所以游客被屏蔽的留言**他自己也看不到了** ——
> 这不是遗漏，而是「游客身份无法持久识别」的必然结果（换个昵称就认不出来了）。
> 只有注册用户才有稳定身份，才谈得上「仍能看到自己那条」。

游客点「留言」时，表单上方会固定显示一段黄色提示条：
**「匿名留言会正常发出，站主能看到。但发布后不能修改、不能删除，你也不会再找回它。想要能编辑就先注册。」**
这句是需求里明确要求的，所以放在提交按钮正上方，不藏进 tooltip。

> ⚠️ 为什么要主动提示：如果不说，访客会以为自己发的是可编辑的公开评论，
> 第二天回来看发现改不了也不知道找谁，会以为系统坏了。诚实地告诉他规则，这个功能才站得住。

### 关于我：配置文件 or 后台编辑

**默认读 `application.yml` 的 `rag.site.*`；一旦在 `/admin/profile` 保存过，就改读数据库。**
数据库里的记录（`kb_profile` 单行表）优先，表为空时回落配置 —— 所以你既可以改 yml
（改完重启），也可以登录后台点几下（立即生效）。页面上会标出当前用的是哪一份。

「恢复默认」按钮会删掉数据库那一行，重新回落到 yml。

### 个人内容：照片墙 / 项目 / 简历

主页的这三块内容不写在配置文件里，而是登录后台在 **`/admin/media`** 直接上传维护：

| 内容 | 主页位置 | 支持 |
| --- | --- | --- |
| **照片墙** | 「随手拍的」瀑布流，点击看大图 | jpg/png/webp/gif，单张 ≤8MB，可配文字说明 |
| **项目展示** | 「做过的项目」卡片网格 | 标题/说明/截图/技术栈/源码地址/在线演示 |
| **简历下载** | 页脚下载区 | pdf/doc/docx/zip，≤20MB，自动统计下载次数 |

三项都支持 `visible` 开关：关掉后访客看不到，但记录和文件还在（不用删了重传）。

### 文件存哪里：本地磁盘 or 阿里云 OSS

由 `rag.storage.type` 决定，**代码不用改**：

```yaml
rag:
  storage:
    type: local          # local → 存服务器磁盘，URL 走 /media/**
    local-dir: ./data/media

# 或者
rag:
  storage:
    type: oss
    oss:
      endpoint: oss-cn-hangzhou.aliyuncs.com   # 不带 https://
      bucket: my-bucket
      access-key-id: LTAI...
      access-key-secret: ...
      public-base: ""    # 留空自动拼 https://{bucket}.{endpoint}
```

**建议生产用 oss**：2C2G 轻量机带宽通常只有 3-5Mbps，一张 3MB 的手机照要传 5 秒，访客多了会互相拖慢整站。本地存储只适合「还没申请 OSS」和本地开发。

⚠️ OSS 凭据四项缺一个，启动时直接报错退出（`rag.storage.type=oss 但凭据不全`），
不会带病运行。AccessKey 建议用 RAM 子账号，只给这个 bucket 的
`oss:PutObject / oss:GetObject / oss:DeleteObject` 三个权限，别用主账号。

### 站点资料怎么改

「关于我」有两条维护路径，**优先顺序是数据库 > 配置文件**：

1. **登录后台 `/admin/profile` 在线编辑**（推荐）—— 保存即生效，不用重启
2. 或改 `application.yml` 的 `rag.site.*`，改完重启（数据库里没记录时才生效）

配置文件长这样：

```yaml
rag:
  site:
    title: "Demo Site"                  # 浏览器标题 + 页脚
    owner: "Demo"                       # 主页大标题「你好，我是 xxx」
    avatar-text: "D"                    # 头像文字（没上传头像图片时用首字母）
    tagline: "记录、思考与分享"          # 名字下面一句话
    intro: "第一段自我介绍"
    tags: ["Java", "Spring Boot"]       # 标签
    about:                              # 更多段落，每行一段
      - "第二段"
      - "第三段"
    links:                              # 外链卡片
      - {label: "GitHub", url: "https://github.com/xxx"}
```

对应 `com.example.kb.config.SiteProperties`（`@ConfigurationProperties(prefix="rag.site")`）。

> ⚠️ 后台保存过的内容存在 `kb_profile` 单行表里。此时再改 yml 是**没有效果的**
> （页面会显示「当前用的是后台保存的内容」）。想切回 yml 就点「恢复默认」。

---

## 二、技术栈

| 层 | 选型 | 版本 |
| --- | --- | --- |
| 后端 | Spring Boot | 3.5.15 |
| AI 框架 | Spring AI | 1.1.8 |
| 数据库 | PostgreSQL + pgvector | 16 |
| 前端 | Vue 3 + Element Plus + Pinia + Vite | 3.5 / 2.9 / 2.3 / 6.0 |
| JDK | OpenJDK | 17 |
| 大模型 | 阿里云百炼 `qwen3.7-flash` | OpenAI 兼容端点 |
| 向量模型 | 阿里云百炼 `qwen3.7-text-embedding-flash` | 1024 维 |

### 免费额度可用的模型（实测）

| 模型 | 状态 | 备注 |
| --- | --- | --- |
| `qwen3.7-flash` | 200 | 当前默认，响应最快（约 1.1s） |
| `qwen3.8-flash` | 200 | 也可以用 |
| `deepseek-v4.1-flash` | 200 | 稍慢 |
| `qwen3.8-27b` / `qwen3.8-max-0902` / `kimi-k3` | 200 | 有额度 |
| `qwen3.7-plus` | 403 | `AllocationQuota.FreeTierOnly`，不在免费清单 |

---

## 三、快速开始

### 1. 起数据库

```bash
docker run -d --name rag-pg --restart unless-stopped \
  -p 127.0.0.1:5432:5432 \
  -e POSTGRES_PASSWORD="$RAG_DB_PASSWORD" -e POSTGRES_DB=rag -e POSTGRES_USER=postgres \
  --memory=600m \
  pgvector/pgvector:pg16
```

建表：

```bash
docker cp src/main/resources/db/schema.sql rag-pg:/tmp/schema.sql
docker exec rag-pg psql -U postgres -d rag -f /tmp/schema.sql
```

> 全新安装用 `schema.sql`（含全部 13 张表）；从旧版本升级用增量脚本（只加不删，可重复执行，**按版本号顺序跑**）：
>
> | 脚本 | 加了什么 |
> | --- | --- |
> | `migrate-v2-personal.sql` | `kb_user` / `kb_visit` |
> | `migrate-v3-media.sql` | `kb_photo` / `kb_project` / `kb_download` |
> | `migrate-v4-profile-moments.sql` | `kb_profile` / `kb_moment` / `kb_moment_comment` |
> | `migrate-v5-avatar.sql` | `kb_profile.avatar_url` |
> | `migrate-v6-chat-log.sql` | `kb_chat`（问答记录） |
> | `migrate-v7-comments-board.sql` | `kb_comment`（统表）+ `kb_user.device_id` + 旧留言迁移 |
>
> ⚠️ v7 之后 `kb_moment_comment` 表**保留但不再使用**（数据已迁到 `kb_comment`）。
> 确认数据无误后可手工 `DROP TABLE kb_moment_comment;`。

### 2. 配置

```bash
export OPENAI_API_KEY=sk-xxxx
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
export RAG_ADMIN_PASSWORD='你的密码'

# 可选：用 OSS 存照片/简历（不设就走服务器本地磁盘）
export RAG_STORAGE_TYPE=oss
export RAG_OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
export RAG_OSS_BUCKET=my-bucket
export RAG_OSS_AK_ID=LTAI...
export RAG_OSS_AK_SECRET=...
```

> **`OPENAI_BASE_URL` 结尾不要带 `/v1`**。Spring AI 默认请求路径是 `/v1/embeddings`，
> 百炼的兼容端点本身已含 `/v1`，再拼一次就变成 `.../v1/v1/embeddings` 导致 404。

### 3. 构建运行

```bash
# 后端（含前端产物）
mvn clean package -DskipTests
java -Xms256m -Xmx512m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m \
     -jar target/rag-kb-0.0.1-SNAPSHOT.jar
```

```bash
# 前端开发（独立跑，proxy 到后端 8080）
# 前端源码在 rag-kb-ui/，和后端同一个仓库（源码入库，node_modules 与 dist 不入库）
cd rag-kb-ui && npm install && npm run dev
```

访问 http://localhost:8080 （前端开发模式是 http://localhost:5173，Vite 会把 /api 代理到 8080）

> 本地开发是纯 HTTP（没证书），HTTPS 只在线上那台机器上由 nginx 终结。
> 所以 `application.yml` 里没有任何 `server.ssl.*`。`server.address` 默认 `127.0.0.1`
> （可用环境变量 `SERVER_ADDRESS` 覆盖）—— 本地跑同样只监听回环，`http://localhost:8080` 正常访问，
> 但同一局域网的其他设备连不上。想让手机连本机调试就 `export SERVER_ADDRESS=0.0.0.0`。

---

## 四、数据模型

```
kb_document（文档）           kb_chunk（切块）              vector_store（向量）
┌────────────────┐          ┌──────────────────┐        ┌────────────────────┐
│ id             │──1:N──▶  │ id               │        │ id (uuid)          │
│ filename       │          │ document_id      │        │ content            │
│ content_hash   │          │ chunk_index      │        │ metadata (json)    │
│ status         │          │ content          │        │ embedding vector   │
└────────────────┘          │ vector_id  ──────┼──1:1──▶│ (1024 维)          │
                            └──────────────────┘        └────────────────────┘
                                    ▲
                                    │ metadata->>'chunkId' 关联
                                    └── 双轨

kb_user（账号表：站主 1 行 + 注册用户）
  ├ device_id    浏览器指纹，部分唯一索引 uk_kb_user_device → 一个浏览器一个账号
  └ register_ip  注册来源 IP，24 小时内同一 IP 最多 2 个账号

kb_visit（访客日志，只增不删）
kb_chat（问答记录：问题 / 回答全文 / 引用 JSON / 耗时 / 是否答完）

kb_photo（照片墙）   kb_project（项目）   kb_download（简历/作品集）

kb_profile（关于我，单行 id=1）

kb_moment（日常动态）──1:N──▶ kb_comment（留言，moment_id 为 null 时是留言板）
                                 ├ visibility  PUBLIC / PRIVATE（悄悄话）
                                 ├ status      VISIBLE / HIDDEN（站主屏蔽）
                                 └ user_id     有值=注册用户（可编辑/可看自己被屏蔽的），null=游客
```

**三表职责**：

| 表 | 职责 | 谁维护 |
| --- | --- | --- |
| `kb_document` | 文档元数据、状态机、上传查重 | 我们 |
| `kb_chunk` | 结构化查询（分页、按文档筛、关键词检索） | 我们 |
| `vector_store` | 相似度检索，只服务向量查询 | Spring AI 自动建表 |

> **写库顺序是硬约束**：先落 `kb_chunk` 拿自增 id，再写向量（把 id 塞进 metadata.chunkId），
> 最后回填 `kb_chunk.vector_id`。反过来的话，写向量成功但落库失败会留下孤儿向量 ——
> 向量表没外键，永远清不掉。

---

## 五、接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/site` | 公开 | 主页信息（站名 + 站主资料 + 文档/片段/访客数 + 最近收录 + **照片/项目/下载** + 当前 IP 配额） |
| GET | `/api/chat/quota` | 公开 | 当前 IP 剩余问答次数 |
| POST | `/api/chat` | 公开 | SSE 流式问答（**有 IP 限流**） |
| GET | `/api/media/public/photos` | 公开 | 照片墙（只含 visible=true） |
| GET | `/api/media/public/projects` | 公开 | 项目展示（只含 visible=true） |
| GET | `/api/media/public/downloads` | 公开 | 下载列表（只含 visible=true） |
| POST | `/api/media/public/downloads/{id}/hit` | 公开 | 下载计数 +1（前端 fire-and-forget） |
| GET | `/api/moments/public` | 公开 | 日常动态流（只含 visible=true） |
| GET | `/api/comments/public?limit=&offset=` | 公开 | 留言板列表（**游客看不到悄悄话**；带 token 能看到自己被屏蔽的） |
| POST | `/api/comments/public` | 公开 | 提交留言板留言（游客可发） |
| GET | `/api/comments/public/moments/{momentId}` | 公开 | 某条动态下的公开留言 |
| POST | `/api/comments/public/moments/{momentId}` | 公开 | 给某条动态留言 |
| POST | `/api/auth/login` | 公开 | 登录换 token + 完整身份（含 `admin`） |
| POST | `/api/auth/register` | 公开 | 注册普通用户（设备指纹唯一 + 同 IP 24h 限 2） |
| POST | `/api/auth/logout` | 公开 | 登出（幂等） |
| GET | `/api/auth/me` | 公开 | 当前登录状态，未登录 `data:null` |
| POST | `/api/auth/password` | 登录用户 | 改密码（成功后踢掉所有会话） |
| GET | `/api/comments?unreadOnly=&boardOnly=` | 站主 | 后台留言列表（含悄悄话 + 已屏蔽） |
| PUT | `/api/comments/{id}` | 登录用户 | 改自己的留言（站主可改任何一条） |
| DELETE | `/api/comments/{id}` | 登录用户 | 删自己的留言（站主可删任何一条） |
| PUT | `/api/comments/{id}/status` | 站主 | 屏蔽 / 恢复（`VISIBLE` / `HIDDEN`） |
| GET | `/api/comments/unread` | 站主 | 未读数（后台红点） |
| POST | `/api/comments/read?id=` | 站主 | 标记已读（不传 id = 全部已读） |
| GET | `/api/chat/logs?onlyVisitor=` | 站主 | 问答记录列表（含问题/回答预览/耗时） |
| GET | `/api/chat/logs/{id}` | 站主 | 问答详情（回答全文 + 引用列表） |
| DELETE | `/api/chat/logs/{id}` | 站主 | 删除一条记录 |
| POST | `/api/chat/logs/clear` | 站主 | 清空全部记录 |
| GET | `/api/documents` | 站主 | 文档列表 |
| GET | `/api/documents/{id}` | 站主 | 文档详情 |
| GET | `/api/documents/{id}/chunks?page=&size=` | 站主 | 切块分页 |
| POST | `/api/documents` | 站主 | 上传（multipart，字段 `file`） |
| DELETE | `/api/documents/{id}` | 站主 | 删除（连带向量） |
| POST | `/api/documents/{id}/reindex` | 站主 | 重建索引 |
| GET | `/api/media/photos` | 站主 | 照片列表（含隐藏项） |
| POST | `/api/media/photos` | 站主 | 上传照片（multipart：`file` + `caption`） |
| PUT | `/api/media/photos/{id}` | 站主 | 改说明 / 排序 / 显隐 |
| DELETE | `/api/media/photos/{id}` | 站主 | 删除（文件一并删） |
| GET | `/api/media/projects` | 站主 | 项目列表（含隐藏项） |
| POST | `/api/media/projects/cover` | 站主 | 单独上传项目截图，返回 `{url}` |
| POST | `/api/media/projects` | 站主 | 新建项目 |
| PUT | `/api/media/projects/{id}` | 站主 | 编辑项目（整体覆盖） |
| DELETE | `/api/media/projects/{id}` | 站主 | 删除项目（连带截图） |
| GET | `/api/media/downloads` | 站主 | 下载列表（含隐藏项） |
| POST | `/api/media/downloads` | 站主 | 上传简历（multipart：`file` + `title`） |
| PUT | `/api/media/downloads/{id}` | 站主 | 改标题 / 排序 / 显隐 |
| DELETE | `/api/media/downloads/{id}` | 站主 | 删除（文件一并删） |
| GET | `/api/visit` | 站主 | 访客流水（分页） |
| GET | `/api/visit/summary` | 站主 | 按 IP 聚合 |
| GET | `/api/visit/stats` | 站主 | 总量统计（访问数 / 独立 IP / 提问数） |
| GET | `/api/site/profile` | 站主 | 读「关于我」（含 `fromConfig` 标记） |
| PUT | `/api/site/profile` | 站主 | 保存「关于我」（整行覆盖） |
| POST | `/api/site/profile/reset` | 站主 | 删除数据库那份，回落到 yml |
| GET | `/api/moments` | 站主 | 动态列表（含隐藏项 + 评论数 + 同步标记） |
| POST | `/api/moments/upload` | 站主 | 上传配图/视频（multipart：`file`） |
| POST | `/api/moments` | 站主 | 发动态（可 `syncToPhotos`） |
| PUT | `/api/moments/{id}` | 站主 | 改文字 / 显隐（不支持换媒体） |
| POST | `/api/moments/{id}/sync-to-photos` | 站主 | 补发到照片墙 |
| DELETE | `/api/moments/{id}` | 站主 | 删除（连带照片墙那条 + 媒体文件） |


### SSE 协议

```
event: sources
data: [{"chunkId":31,"documentId":8,"filename":"x.md","pageNumber":1,
        "headingPath":"标题 > 子标题","score":0.6,"preview":"..."}]

event: token
data: 回答片段

event: done
data: {"sourceCount":1,"chatTokens":0,"elapsedMs":413,"remainingQuota":19}
```

`chatTokens` 恒为 0：百炼的 stream 模式不返回 usage（每帧 `usage: null`）。

---

## 六、关键设计取舍

### 1. 为什么不用 Spring Security

需求只有「一个管理员」：没有角色体系、没有方法级注解、没有 RememberMe。
引全套 Security 要多约 30MB 类加载 + 一堆 FilterChain 配置，2C2G 上不划算。
自己写 BCrypt 校验 + 内存 token + HandlerInterceptor，代码量小，逻辑一眼看完。

### 2. 为什么 token 存内存不落库

token 表要定期清理、要处理并发踢下线、要考虑多节点共享。单机单人场景，
一个 `ConcurrentHashMap` 全部搞定，零维护成本。重启即失效 ——
对「自己的知识库」这是可接受的，反而更安全。

### 3. 为什么 IP 归属地异步补

查归属地要调外部 HTTP 接口，快则 100ms 慢则超时 5s。放同步链路会让访客打开主页卡住 ——
这是「记录访客」的成本，不该由访客承担。同步只写 IP+时间（几十毫秒），
归属地交给后台每分钟补一次。

接口降级链（服务器上实测）：`api.ip.sb`（首选，字段最全）→ `ipinfo.io`（备用）。
`ip-api.com` 在国内云服务器上超时，不可用。内网 IP（127.x / 10.x / 192.168.x）直接标 FAILED 跳过。

### 4. 为什么关掉模型的「思考模式」

qwen3.x / deepseek-v4.1 默认是推理模型：先吐几十段 `reasoning_content` 再吐正文。
对「打开小窗口问一句」这个场景等于十几秒白屏。实测关掉后：

| 模型 | 思考开 | 思考关 |
| --- | --- | --- |
| `qwen3.8-flash` | 1860ms，首个 content 为空 | 1176ms，立即出字 |
| `qwen3.7-flash` | — | 1078ms |

实现方式是 `OpenAiChatOptions.extraBody(Map.of("enable_thinking", false))` ——
这个 map 上有 `@JsonAnyGetter`，会被扁平化写进请求体顶层。

### 5. 为什么前端不打成独立服务

部署时 `rag-kb-ui/dist` 拷进 `src/main/resources/static/`，由 Spring Boot 托管。
代价是改前端要重新 build + 打包 jar。
开发时仍是分离的（Vite 5173 proxy → 8080）。

原本为了「省 30MB 内存 + 一个进程槽位」决定不引 nginx，**2026-10-21 加 HTTPS 时推翻了这个决定**：
这台机器没有域名，只能签 Let's Encrypt 的 IP 证书，有效期 160 小时（约 6.7 天）。
如果让 Tomcat 自己加载证书，每 4 天续一次就得重启一次应用。
证书改由 nginx 持有后，续期只要 `nginx -s reload`，Java 进程不动。
nginx 实测常驻约 8MB —— 8MB 换掉"每 4 天重启一次站点"，这笔账划算。

### 6. 为什么限流用滑动窗口

固定窗口（每分钟清零）有边界突刺：23:59 打 20 次 + 00:00 打 20 次 = 40 次集中在一分钟。
滑动窗口按「最近 3600 秒内有多少次」算，没有这个洞。

### 7. 为什么问答做成悬浮窗而不是独立页面

需求是「**个人网站**，其次才是 RAG」。原来整页一个大聊天框，主次颠倒了 ——
访客进来第一眼应该看到「这是谁」，而不是一个聊天界面。

现在 `/` 是自我介绍页（首屏 / 关于我 / 知识库统计），问答是右下角 56px 的气泡，
点开是 380×540 面板。好处：
- 主页能放任何内容，不被聊天框绑架；日后加文章列表、作品集都不用重构
- 浮窗可以出现在任何页面（后台也能用），不受路由限制
- 移动端浮窗自动变全屏

### 8. 为什么「关于我」默认读配置、改过就读数据库

`rag.site.*` 绑到 `SiteProperties`，改名/改简介只改 yml 重启，**不用重新 build 前端**。
后来又加了「后台在线编辑」，于是变成两级：

| 状态 | 数据来源 |
| --- | --- |
| `kb_profile` 表为空 | `application.yml` 的 `rag.site.*`（`fromConfig=true`） |
| 后台点过保存 | `kb_profile` 那一行（`fromConfig=false`），配置被忽略 |

**为什么不做成「配置 + 数据库合并」**：合并要定义每个字段谁优先，`about` 是列表，
合并语义更模糊（追加？替换？）。两级切换一句话说得清，用户也看得懂界面上的提示。

`kb_profile` 做成 **id 恒为 1 的单行表**（`CHECK (id = 1)`），不用三张子表：
「关于我」整行读、整行写，`about`/`links`/`tags` 存 JSONB —— 它们只是展示用的有序列表，
不需要按段落查询。拆成子表会把「保存一次」变成三条 DELETE + N 条 INSERT。

### 9. 为什么留言的可见性规则不放在 Controller

需求演进过一次：最初是「访客留言只有站主能看到」，后来变成「用户可自行选择是否公开，
站主可屏蔽，匿名留言不保存记录」。规则变复杂后，**最容易出错的地方是把可见性判断
散在各处**——列表接口写一遍、详情接口写一遍、提交接口再写一遍，改规则时漏一处就是隐私漏洞。

所以可见性收敛成两个地方：

**① Repository 里的一条 SQL 片段**（`KbCommentRepository.VISIBLE_CLAUSE`）：

```sql
AND (visibility = 'PUBLIC' AND status = 'VISIBLE'
     OR (user_id IS NOT NULL AND user_id = ?))
```

这一条同时表达了两个规则：悄悄话别人查不到；被屏蔽的留言作者本人仍能查到。
所有列表查询都拼它，没有第二个地方判断可见性。

> ⚠️ **这里的 `?` 必须绑 0 而不是 null**。SQL 三值逻辑里 `user_id = NULL` 结果是
> `NULL` 而不是 `false`，整个 `OR` 会退化成 `NULL`，连 PUBLIC 那半边都匹配不上 ——
> 游客会看到空列表。这是实测踩出来的。

**② Service 里决定「我是谁」**：游客 → `userId=null`；注册用户 → 用 token 里的 id。
游客的 `visibility` 字段在 Service 里被**强制改回 PUBLIC**，不信任前端传什么
（悄悄话的语义是「注册用户对站主说」，游客说了也收不到回复）。

另外两处刻意的防护：

1. **提交接口的昵称**：注册用户发留言时，忽略前端传的 `nickname`，用账号的
   `displayName` 覆盖 —— 否则任何人都能顶着别人的名字发言
2. **访客接口不返回 IP**：公开别人的 IP 是隐私问题。IP 只在站主后台的留言列表里出现

### 10. 为什么「匿名留言不保存记录」最后还是存了库

需求原话是「匿名留言不会保存记录」。但完全不写库的话，站主在后台就永远看不到这条
留言 —— 那留言本身就没有意义了（留言的目的就是让站主看到）。

实际实现是：**照常存库给站主看，但留言者无法编辑、无法删除、无法找回，
也不会收到任何回复通知**。界面文案明确写出这一点，不误导人。

换句话说，「不保存记录」被解读成「不给你留后路」，而不是「站主看不见」。

### 11. 为什么注册门槛用设备指纹 + IP 双软限

需求是「同一设备不能注册多账号」。严格做需要硬件级设备识别，浏览器做不到。
可选项：

| 方案 | 效果 | 成本 |
| --- | --- | --- |
| 只记 IP | 校园网 / NAT 后面几十个人共用一个 IP，全被挡 | 零 |
| 只记 localStorage | 清缓存就绕过 | 零 |
| **指纹 + IP 双软限** | 清缓存换指纹能绕过，但会被 IP 那道挡住一部分 | 零 |

选了第三种。设备指纹用 UA / 语言 / 屏幕 / 时区 / 核数做 FNV-1a 哈希，
**不读 canvas 和字体列表** —— 前者是追踪技术（很多浏览器插件会拦截），
后者区分度低还慢。数据库层用**部分唯一索引**兜底：

```sql
CREATE UNIQUE INDEX uk_kb_user_device ON kb_user (device_id) WHERE device_id IS NOT NULL;
```

> ⚠️ 必须是**部分索引**：手工建的账号（比如站主）`device_id` 是 NULL，
> 普通唯一索引下多行 NULL 会互相冲突，一个都插不进去。

**真正的兜底是「注册了也只能留言」**：普通用户没有站主权限，所有管理接口返回 403。
注册多个账号除了刷留言没有别的作用，而刷留言有 IP 限流。

### 12. 为什么开放注册后必须停用「清理非管理员账号」

原来的 `AdminInitializer` 每次启动都执行 `DELETE FROM kb_user WHERE username <> '<管理员账号>'`
—— 单用户时代这是对的，能防止有人手工往库里塞账号。开放注册后它会**把所有注册用户删光**，
每次重启清一次，等于注册功能白做。

已停用该调用（方法体保留并标 `@Deprecated`，将来回单用户模式改一行调用即恢复）。
用户隔离改由「路径白名单 + `requireAdmin()`」保证 —— 这个机制不依赖数据库里有几个用户。

### 13. 为什么留言表不再拆「留言板」和「动态评论」两张表

最初是 `kb_moment_comment` 一张表专管动态评论。后来需求加了独立留言板，
两个选择：再建一张 `kb_comment_board`，或者给现有表加可空的 `moment_id`。

选了后者（表名统一成 `kb_comment`）。**一张表的代价是每个查询要带 `moment_id IS NULL`
或 `IS NOT NULL` 条件；两张表的代价是维护两套 Repository、两套 Controller、两套前端调用、
两套测试**。后者是持续的、每个新功能都要付一次的代价，前者是一次性写清楚的。
动态评论总数很少（个人站，几百条量级），拆表带来的查询优化收益接近零。

### 14. 为什么存储抽象成 FileStorage 接口

照片和简历是「用户产生的文件」，和文档（走 RAG 管线）不是一类东西。
写成 `FileStorage` 接口（`put` / `delete` / `keyOf`），实现有 `LocalStorage` 和 `OssStorage`，
由 `StorageConfig` 按 `rag.storage.type` 二选一创建 bean。

**为什么不给两个实现都标 `@Component` + `@ConditionalOnProperty`**：
那样配置写错（比如 `type=oss` 但 key 没填）也能启动，等到第一个访客上传照片才炸，
而且报的是 OSS SDK 的底层错误。现在 `StorageConfig` 里手工校验四项凭据，
缺一个就在启动时 `IllegalStateException`，日志里说得明明白白。

⚠️ 踩过的坑：`LocalStorage` 一开始标了 `@Component`，`StorageConfig` 又 `@Bean` 创建一个，
Spring 报 `required a single bean, but 2 were found` 启动直接失败。
**同一个接口的实现要么全用组件扫描、要么全用 @Bean 手工装配，不能混。**

### 15. 为什么照片/项目/下载都塞进 /api/site

主页首屏一次请求拿全，少 3 个 RTT。手机 4G 下每次 RTT 30-80ms，
4 个串行请求就是多 100-240ms 白屏；而且分开拿会出现「照片出来了但项目还没来」的跳动。

数据量可控：照片走 `visible=true` 且上限 60 条，只返回 URL 和说明，不返回图片本身。

---

## 七、代码结构

按标准 Spring 分层：**controller → service → repository → domain/dto**，
外部依赖（向量库、地理库、解析器、限流、密码散列）收进 `infrastructure`。

```
rag-kb/                                  # 仓库根：前后端同一个 git 仓库
├── src/main/java/com/example/kb/        # 后端
│   ├── controller/                      # 8 个：Document / Chat / Auth / Visit / Site / SiteContent / Media / Comment
│   │   └── 只做「收参数 → 调 Service → 包 R」，不碰 SQL、不拼业务、不返回实体
│   ├── service/                         # 12 个：文档、索引、问答、检索、引用、认证、访客、站点、内容、资料、动态、留言
│   │   └── 流程编排 + 业务规则（限流策略、分页上限、容错口径都在这里）
│   ├── repository/                      # 10 个：文档/切块/用户/访客/资料/动态/留言/照片/项目/下载，只写 SQL
│   ├── domain/                          # 10 个实体
│   │   └── ⚠️ 不出 Service：内部字段（contentHash、passwordHash）无法从类型上泄露
│   ├── dto/                             # 接口契约，全部 record 或纯 POJO
│   │   ├── auth/ chat/ comment/ document/ site/ visit/ media/
│   ├── infrastructure/                  # 11 个：外部依赖适配
│   │   ├── vectorstore/                 # VectorStoreGateway 封装 PgVectorStore
│   │   ├── parser/                      # DocumentParser（PDF/MD/TXT）+ Chunker
│   │   ├── geo/                         # GeoIpService + ClientIpResolver
│   │   ├── security/                    # PasswordHasher（BCrypt）+ TokenStore
│   │   ├── ratelimit/                   # IpRateLimiter（滑动窗口）
│   │   └── storage/                     # FileStorage + LocalStorage + OssStorage
│   ├── config/                          # AiConfig / AsyncConfig / WebConfig / StorageConfig
│   │   └── AuthInterceptor / AdminInitializer / SiteProperties
│   └── common/                          # R 统一响应 + 全局异常
├── src/main/resources/
│   ├── application.yml
│   ├── db/schema.sql                    # 完整建表（全新安装）
│   ├── db/migrate-v2-personal.sql       # 增量：kb_user / kb_visit
│   ├── db/migrate-v3-media.sql          # 增量：kb_photo / kb_project / kb_download
│   ├── db/migrate-v4-profile-moments.sql      # 增量：kb_profile / kb_moment / kb_moment_comment
│   ├── db/migrate-v5-avatar.sql         # 增量：kb_profile.avatar_url
│   ├── db/migrate-v6-chat-log.sql       # 增量：kb_chat（问答记录）
│   ├── db/migrate-v7-comments-board.sql # 增量：kb_comment（统表）+ kb_user.device_id
│   └── static/                          # 前端构建产物（build 时从 rag-kb-ui/dist 拷进来）
├── deploy/https/                        # 线上 HTTPS（Let's Encrypt IP 证书 + nginx）
│   ├── install-https.sh                 # 一键安装，可重复执行（--staging / --dry-run）
│   ├── renew-cert.sh                    # 续期，cron 每天 03:17 调
│   ├── nginx.conf                       # 整份替换 /etc/nginx/nginx.conf
│   ├── rag-kb.conf                      # 站点配置（80 跳 443 + 443 反代 + ACME）
│   └── nginx-bootstrap.conf             # 只签证书前用（只有 80，其它 503）
├── docs/screenshots/                    # README 里用的截图
└── rag-kb-ui/                           # 前端源码（⚠️ 必须入库，理由见下方「为什么要入库」）
    └── src/
        ├── views/HomeView.vue           # 个人主页（首屏/关于我/日常/项目/照片墙/知识库）
        ├── views/MomentsView.vue        # 游客看的日常动态流 + 留言表单
        ├── views/BoardView.vue          # 独立留言板（三种身份三套提示）
        ├── components/ChatWidget.vue    # 右下角悬浮问答窗
        ├── views/DashboardView.vue      # 后台概览（数据四卡 + 上传弹窗）
        ├── views/LoginView.vue          # 登录（站主或注册用户）
        ├── views/RegisterView.vue       # 注册普通用户（设备指纹）
        ├── views/DocumentsView.vue      # 文档管理
        ├── views/DocumentDetailView.vue # 切块查看
        ├── views/MediaView.vue          # 我的内容（照片墙/项目/简历）
        ├── views/MomentsAdminView.vue   # 日常动态管理（发布 + 看留言）
        ├── views/CommentsView.vue       # 留言管理（含悄悄话/已屏蔽，可筛选可屏蔽）
        ├── views/ChatLogsView.vue       # 问答记录（访客问了什么、答了什么）
        ├── views/ProfileEditView.vue    # 关于我（在线编辑 + 头像）
        ├── views/VisitsView.vue         # 访客记录
        ├── utils/device.js              # 浏览器设备指纹（FNV-1a，零依赖）
        ├── layouts/AdminLayout.vue      # 后台布局（顶栏 + 改密 + 未读红点）
        ├── stores/auth.js               # 登录状态
        ├── api/index.js                 # 接口 + SSE 解析器
        └── router/index.js              # 路由 + 登录守卫
```

### 为什么要入库前端源码

前端全部 31 个源文件（0.29 MB）曾经裸放在 `rag-kb/` 的**外面**、不在任何 git 仓库里。
后果是：构建产物 `src/main/resources/static/` 有完整版本历史，**反而源码没有** ——
改坏了没法回滚，换机器也没有可拉取的前端。

现在的规矩：`.gitignore` 只忽略 `rag-kb-ui/node_modules/` 和 `rag-kb-ui/dist/`，
`src/` 下所有 `.vue` / `.js` 必须入库。判断标准很简单 ——
**能靠 `npm install` + `npm run build` 重新生成的，忽略；不能的，入库。**

### 分层的四条硬规则

改代码时这四条别破，破了就是下次重构的起点：

| # | 规则 | 为什么 |
| --- | --- | --- |
| 1 | Controller 不 import `repository` / `domain` | 一旦 Controller 能直接查库，业务规则就开始往 Controller 里长 |
| 2 | Controller 的返回值只能是 `R` 包着的 DTO | 实体带内部字段（`contentHash`、`passwordHash`），某次手滑 `R.ok(user)` 就泄露了 |
| 3 | Service 不返回 `Map<String,Object>` | 字段名没有编译期检查，前端拼错一个字母就是一片空白 |
| 4 | Repository 不 import `service` / `controller` | 反向依赖意味着「改 Service 要动 SQL 层」，重构会传染 |

前三条已用脚本审计过，第 4 条靠代码评审。

---

## 八、踩过的坑

| # | 坑 | 原因 | 解法 |
| --- | --- | --- | --- |
| 1 | embedding 404 | base-url 带 `/v1`，实际请求 `.../v1/v1/embeddings` | base-url 去掉 `/v1` |
| 2 | chat 403 | `qwen3.7-plus` 不在免费清单 | 换 `qwen3.7-flash` |
| 3 | 前端刷新 404 | Vue Router history 模式无回退 | `WebConfig.addViewControllers` 转发 `/index.html` |
| 4 | 配置不生效 | `spring.rag.*` 不是 Spring 命名空间 | 移到顶层 `rag.*` |
| 5 | 插入不返回主键 | PG 驱动对 `RETURN_GENERATED_KEYS` 返回整行 | 用 `new String[]{"id"}` |
| 6 | 首字空白十几秒 | 推理模型先吐 `reasoning_content` | `extraBody` 传 `enable_thinking:false` |
| 7 | 序列化丢 null 字段 | Jackson 默认省略 null | `@JsonInclude(ALWAYS)` on R |
| 8 | 匿名 `/api/auth/me` 空 body | Controller 返回裸 null，Spring 给空响应 | 包一层 `R.ok(null)` |
| 9 | 定时任务不跑 | 少了 `@EnableScheduling` | 启动类加注解 |
| 10 | 前端 `</main>` 闭合错 | `</main>` 两次，缺 `</section>` | vue 编译器报 `Element is missing end tag` |
| 11 | 重名方法编译失败 | 已存在 `countByDocumentId` 返回 int 版本 | 删掉新加的 long 版本，用现有的 |
| 12 | 启动报 `2 were found: localStorage, fileStorage` | `LocalStorage` 标了 `@Component`，`StorageConfig` 又 `@Bean` 造了一个 | 去掉 `@Component`，统一由 `StorageConfig` 装配 |
| 13 | 图片 404 变成 500 + 日志刷 ERROR | `NoResourceFoundException` 被 `Exception` 兜底 | `GlobalExceptionHandler` 显式处理返回 404 |
| 14 | PDF 存成 `.bin` | 从 MIME 推扩展名，浏览器给的是 `application/octet-stream` | 用原文件名的扩展名，不用 MIME 推 |
| 15 | `buildKey` 重名编译失败 | 同签名 `(String,String)` 定义两次，一个收 MIME 一个收 ext | 只留一个收 ext 的，调用处显式 `extensionFor(mime)` |
| 16 | 公开端点仍 401 | `/api/moments/public` 没有结尾斜杠，前缀 `/api/moments/public/` 匹配不到 | `PUBLIC_PATHS` 里单独列一条精确路径 |
| 17 | 编译报 `找不到符号 变量 photoCaption` | 方法参数叫 `syncToPhotos`，局部变量名 `photoCaption` 拼错成裸标识符 | 取 `req.getPhotoCaption()`，同时直接用参数 `syncToPhotos` |
| 18 | `MomentPage(...) { }.withItems(...)` 编译不过 | record 没有 `withItems`，那是编造的链式写法 | 单独定义 `CommentPage`，直接 `new` |
| 19 | 登录响应没有 `admin`、`userId` 是 null | Controller 只 set 了 token + username，没填其余字段 | Service 返回 `LoginSuccess` record，Controller 一次填全 5 个字段 |
| 20 | 游客看留言板一片空白 | SQL 里 `user_id = NULL` 结果是 NULL 不是 false，`OR` 整条退化成 NULL | `viewerIdOrZero()` 把 null 绑成 0 |
| 21 | 提交留言后拿不到 id，无法立即编辑 | 提交接口只返回提示语 | 改返回 `SubmitResult(id, notice, mine)` |
| 22 | 全新库跑 v7 迁移报 `relation does not exist` | 迁移脚本裸 `INSERT ... FROM kb_moment_comment`，新库没这张表 | 包进 `DO $$ ... IF EXISTS ... END $$` |
| 23 | `git add -A` 报 `short read while indexing NUL` | `scp -o UserKnownHostsFile=NUL` 在 Windows 上真的创建了名为 NUL 的文件 | scp 改用 `$env:TEMP\known_hosts`；删文件用 `[System.IO.File]::Delete('\\?\...\NUL')` |
| 24 | 装 `http2 on;` 后 nginx 起不来 | 那是 nginx 1.25.1+ 的指令，CentOS 8 的 AppStream 仓库里只有 1.14.1 | 写成 `listen 443 ssl http2;`（1.14 时代 HTTP/2 是 `listen` 的参数） |
| 25 | ACME 校验文件 404，证书签不下来 | 发行版自带的 nginx 配置有一个 `listen 80 default_server` 块，按 IP 访问会落到它 | 整份替换 `nginx.conf`，80 段显式声明 `default_server` 并路由 `.well-known/acme-challenge/` |
| 26 | certbot 报 `Failed to resolve 'acme-staging-v02...'` | 容器内 DNS 瞬时抖动（宿主解析正常，容器共享宿主机内网 DNS） | 不改配置，重试即成功；docker 的 `--entrypoint sh` 才能进容器跑命令 |
| 27 | 浏览器控制台一直有 404 红字 | 没有 favicon，浏览器默认去要 `/favicon.ico` | `index.html` 里内联一个 data URI 的 SVG 图标，不新增文件 |
| 28 | 加 nginx 后所有访客 IP 变成 127.0.0.1 | `rag.visit.trust-proxy` 的语义变了（以前是"没代理却信 XFF"，现在是"确实有代理"） | 保持 true，但 nginx 必须用 `$remote_addr` **覆盖** XFF，不能用 `$proxy_add_x_forwarded_for` 追加 |
| 29 | 加 HTTPS 后 `http://IP:8080` 仍能明文访问 | Tomcat 默认监听 `0.0.0.0`，云安全组也还开着 8080 | `server.address: 127.0.0.1` 写进 `application.yml`，公网 8080 直接连不上 |

---

## 九、故障排查

| 症状 | 检查 |
| --- | --- |
| 浏览器报「您的连接不是私密连接」 | `nginx -t` / `systemctl status nginx` / `openssl x509 -in /etc/letsencrypt/live/<你的服务器公网IP>/cert.pem -noout -dates` |
| 证书快过期或续期失败 | `tail -50 /var/log/rag-kb-cert-renew.log`；再查 `/var/log/letsencrypt/letsencrypt.log`，最常见是 80 端口被安全组关了 |
| 站点 502 Bad Gateway | `systemctl is-active rag-kb` —— nginx 只是转发，502 说明后端没起来 |
| `http://IP:8080` 打不开（正常） | 应用已改成 `server.address: 127.0.0.1`，8080 只对本机开放，一律走 `https://IP/` |
| 主页空白 | `curl http://127.0.0.1:8080/`（服务器上）是否有 index.html；jar 内 `BOOT-INF/classes/static/` 是否有文件 |
| 问答 500 | `app.log` 搜 `WebClientResponseException` |
| 问答 403 | 模型是否在免费清单；换 `qwen3.7-flash` |
| 问答 429 | 该 IP 触发限流，等一小时或调 `rag.rate-limit.chat-per-hour` |
| 归属地一直「查询中」 | 检查 `geo_status` 是否为 FAILED（内网 IP 会直接失败，正常） |
| 上传后一直 PENDING | 搜 `IndexingService` 日志找异常；`@Async` 异常不传播，靠日志 |
| 登录一直失败 | `rag.admin.password` 是否配置；看启动日志 `已创建管理员账号` |
| 照片上传 500 | `rag.storage.local-dir` 目录是否有写权限（`chmod 755`）；看日志 `LocalStorage` |
| OSS 上传报 `NoSuchBucket` / `InvalidAccessKeyId` | `rag.storage.oss.*` 四项；endpoint 不带 `https://`，bucket 名是全球唯一的小写+连字符 |
| 照片在主页看不到 | 后台看 `visible` 是否为 false；`/api/site` 返回的 `photos` 是否为空 |
| 图片能上传但打不开 | `rag.storage.type=local` 时确认 `WebConfig` 的 `/media/**` 映射生效：`curl -I .../media/photos/xxx.jpg` 应 200 |
| 向量维度不匹配 | 换 embedding 模型必须同时改 `dimensions` 并删表重建 |
| 「关于我」改了 yml 没效果 | 后台保存过就会读 `kb_profile`，改 yml 被忽略；点「恢复默认」或直接 `DELETE FROM kb_profile` |
| 访客说留言发不出去 | 检查该动态 `visible` 是否为 false（仅自己可见的动态不能留言，返回 400） |
| 动态视频传不上去 | `spring.servlet.multipart.max-file-size` 默认 50MB，视频超过会被拒；前端已先挡一道 |
| 后台留言红点不消失 | 红点 60 秒刷新一次；点进「看留言」或「全部标记已读」后立即消失 |
| 访客看不到悄悄话（正常） | 悄悄话只有站主和作者能查，SQL 层就排除了，这是设计不是 bug |
| 注册报「这个浏览器已经注册过账号了」 | 换浏览器 / 清 localStorage 里的 `kb.device.v1` / 查 `kb_user.device_id` |
| 注册报「这个网络下今天注册的账号有点多了」 | 同 IP 24 小时限 2 个，换网络或等明天；阈值在 `AuthService.REGISTER_IP_MAX` |
| 站主登录后看不到后台入口 | 查登录响应 `admin` 是否为 true；`rag.admin.username` 是否与登录名一致 |
| 重启后注册用户全没了 | 检查 `AdminInitializer.removeOtherUsers()` 是否被重新调用（当前已停用） |
| 留言板列表一片空白 | 查 `KbCommentRepository.viewerIdOrZero` 是否把 null 绑成 0（绑 null 会让 SQL 三值逻辑失效） |

---

## 十、部署到 2C2G 服务器

这套东西的实际部署形态：**一台 2 核 2G 的云服务器，没有域名，裸 IP 上 HTTPS。**

```
浏览器 ──80──► nginx ──301──► https
          ──443─► nginx ──反代──► 127.0.0.1:8080 (Spring Boot, 静态资源也在 jar 里)
                                      └──► 127.0.0.1:5432 (PostgreSQL + pgvector, Docker)
```

几个和本项目强相关的决定：

| 决定 | 理由 |
| --- | --- |
| 前端打进 jar，不起独立服务 | 2C2G 上省掉一个常驻进程和一套部署流程，代价是改前端要重新打包 |
| 后端不走 Docker，PG 走 Docker | 后端是唯一的长驻 Java 进程，systemd 管着最省内存；PG 用 `pgvector/pgvector:pg16` 镜像开箱带扩展，自己编扩展太麻烦 |
| 加 nginx 反代 | Tomcat 只在启动时读证书，不加 nginx 的话 160 小时的 IP 证书每续一次就要重启一次应用 |
| `server.address: 127.0.0.1` | 只靠安全组挡 8080 不够 —— 换机器/改规则/临时排障都可能忘，写进配置才算数 |

内存实测（2 核 2G 机器，1816MB 总）：

| 项 | 占用 |
| --- | --- |
| Java 进程 RSS | 约 410 MB |
| nginx（3 个 worker） | 约 5-8 MB |
| PostgreSQL 容器 | 约 65 MB |
| 系统 + 其他 | 约 155 MB |
| **可用** | **约 890 MB** |

JVM 参数：`-Xms256m -Xmx512m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError`

> `MaxDirectMemorySize=64m` 是生死线：PDFBox 解析大 PDF 用 direct memory，
> 超了直接 OOM。`ExitOnOutOfMemoryError` 让容器状态变干净，否则半死不活更难查。
>
> nginx 是后来加 HTTPS 时引入的。早前「不引 nginx 省 30MB」的判断在这个规模上被推翻了：
> 实际常驻只有几 MB，换来的是「证书续期不用重启 Java 进程」。

**HTTPS 怎么装的见 [deploy/https/README.md](deploy/https/README.md)** ——
为什么给裸 IP 签 IP 证书、为什么必须有 nginx、续期脚本和 cron 怎么工作、出问题怎么查，
都在那一份里。`deploy/https/` 下的三个 nginx 配置和两个脚本是可直接复用的。
