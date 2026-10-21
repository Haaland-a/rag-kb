# 给一台没有域名的服务器装上 HTTPS（Let's Encrypt IP 证书）

> 这套脚本在真实服务器上跑过：一张 Let's Encrypt 签给 **IP 地址**的证书，
> 有效期 160 小时（约 6.7 天），cron 每天凌晨自动续期，续完 `nginx -s reload`，Java 进程不重启。
> 浏览器访问不再有「不安全」警告。
>
> 上线后的实测（Edge 无头 + CDP 真机跑，17/17 通过）：安全上下文 `isSecureContext=true`、
> 零混合内容、HTTP 自动 301、SSE 逐块流式（首块 200–800ms）、浏览器控制台零报错。

## 一、为什么是这种方案

如果服务器**没有域名**，能选的路其实只有三条：

| | 域名的证书（常规做法） | **IP 证书（本方案）** | 自签名证书 |
|---|---|---|---|
| 花钱 | 域名几十块/年 | **0 元** | 0 元 |
| 前置工作 | 域名实名 + ICP 备案，2–3 周 | **无** | 无 |
| 浏览器信任 | ✅ | **✅** | ❌ 每次都弹警告 |
| 有效期 | 90 天（将来 45 天） | **160 小时（约 6.7 天）** | 自己定 |
| 续期 | 自动，零停机 | **自动，零停机** | 手动 |
| 备案合规 | 需要 | **不需要**（IP 直连不涉及域名备案） | 不需要 |

Let's Encrypt 从 **2026-01-15** 起正式支持给 IP 地址签证书（profile = `shortlived`）。
有效期短是这个 profile 的硬性规定，不是配置写错了。

> 有域名的话走常规路线更省心：`--ip-address <IP>` 换成 `-d yourdomain.com`，
> 去掉 `--preferred-profile shortlived`，其余流程（webroot、cron、reload）一模一样。

## 二、为什么必须加 nginx

链路：

```
浏览器 ──http/80──┐
                   ├─→ nginx ──→ 127.0.0.1:8080 (Spring Boot)
浏览器 ─https/443──┘
```

不加 nginx 的话，只能让 Tomcat 自己加载证书（`server.ssl.*`）。问题是 **Tomcat 只在启动时读一次证书文件**，
续期之后必须 `systemctl restart rag-kb` 才能生效。IP 证书每 4 天续一次 = 每 4 天把站点重启一次。

nginx 持有证书，续期只要 `nginx -s reload`（平滑重载，连接不断），Java 进程一次都不用重启。

顺带解决的三件事：
- HTTP 自动 301 到 HTTPS
- 80 端口专门留给 ACME 校验，跟业务端口解耦
- gzip 压缩放在 nginx 层，省 Java 的 CPU

## 三、文件清单

| 文件 | 作用 |
|---|---|
| `install-https.sh` | 一键安装：装 nginx → 建 webroot → 引导配置 → 签证书 → 正式配置 → 装 cron。可重复执行，`--staging` 用测试环境，`--dry-run` 只打印，`--ip` 指定服务器公网 IP |
| `renew-cert.sh` | 续期脚本，cron 每天调用。续完 `nginx -t` 检查通过才 reload，剩余不足 2 天时 exit 2 报警 |
| `nginx.conf` | 整份替换 `/etc/nginx/nginx.conf` |
| `rag-kb.conf` | 站点配置，装到 `/etc/nginx/conf.d/rag-kb.conf`。`__SERVER_IP__` 是占位符，由安装脚本 sed 替换 |
| `nginx-bootstrap.conf` | 只在**第一次签证书之前**用：只有 80 端口，`location /` 返回 503 |

## 四、怎么跑

```bash
# 0. 前置：云服务器安全组放行 80 和 443；80 必须能从公网进来，
#    Let's Encrypt 的 HTTP-01 校验只走 80，这一条不满足后面全白搭。
#    还要装好 docker（certbot 跑在容器里）。

# 1. 先干跑一遍看它要做什么
bash install-https.sh --ip <你的服务器公网IP> --dry-run

# 2. 用测试环境签一张，确认整条链路通（不消耗正式额度）
bash install-https.sh --ip <你的服务器公网IP> --staging

# 3. 正式签
bash install-https.sh --ip <你的服务器公网IP>
```

脚本假定应用装在 `/opt/app`，不是的话用 `--app-dir <目录>` 指定（续期脚本和 cron 会装到那里）。

`SERVER_IP` 也可以走环境变量，不传 `--ip` 时会尝试自动探测本机地址 —— 但云服务器上自动探测拿到的
通常是私网地址，**要签公网 IP 就必须显式传**。

## 五、几个不显然的设计决定

### 5.1 nginx.conf 是整份替换，不是「往里加一段」

CentOS 的 nginx 包自带一个 `listen 80 default_server` 的 server 块（root 指向 `/usr/share/nginx/html`）。
按 IP 访问时 HTTP Host 头就是 IP，匹配不上任何 `server_name`，请求会落到那个 default_server ——
ACME 校验文件变成 404，证书永远签不下来，而且报错信息只会说「验证失败」，完全不提 nginx 默认站点。

这是本项目踩得最久的一个坑：`certbot` 报的是校验失败，看起来像网络/端口问题，实际是 nginx 把请求接到了别处。

### 5.2 HSTS 只给 300 秒

```nginx
add_header Strict-Transport-Security "max-age=300" always;
```

正常网站一般写一年。这里**故意写 5 分钟**：IP 证书 160 小时就过期，万一续期连续失败，
浏览器如果记了一年 HSTS，访客连「高级 → 继续前往」那个忽略警告的按钮都点不了，站点直接打不开。
等换成域名证书、续期稳定之后再调大。

### 5.3 XFF 是覆盖不是追加

```nginx
proxy_set_header X-Forwarded-For $remote_addr;                   # ✅ 覆盖
# proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;    # ❌ 追加
```

`$proxy_add_x_forwarded_for` 会把**客户端自己发来的** `X-Forwarded-For` 追加在后面。
而后端 `ClientIpResolver` 取的是第一段 —— 访客只要随手加一个 `X-Forwarded-For: 1.2.3.4`，
就能让按 IP 限流（每小时 20 次）和访客记录全部失效。用 `$remote_addr` 覆盖，客户端塞什么进来都会被丢掉。

实测过：带 `X-Forwarded-For: 1.2.3.4` 请求 `/api/chat`，数据库里记的仍是真实 IP。

### 5.4 SSE 必须关 buffering

`/api/chat` 是 `text/event-stream` 流式返回。nginx 默认会把响应缓冲起来再发，
表现是「问完等半天，然后所有字一次性蹦出来」。所以：

```nginx
proxy_buffering off;
proxy_cache off;
proxy_read_timeout 300s;
proxy_send_timeout 300s;
proxy_set_header Accept-Encoding "";
```

`Accept-Encoding ""` 是防止上游压缩把流式响应攒成块。

### 5.5 续期为什么每天跑

certbot 对短有效期证书的规则是：剩余时间低于「半衰期」就续。160 小时的半衰期约 80 小时（3.3 天），
所以每天跑一次，实际大约每 4 天真正续一次，其余几次是空转（3 秒左右）。
cron 写 `17 3 * * *`，避开整点的调度高峰。

### 5.6 为什么不用 certbot 的 `--deploy-hook`

certbot 跑在容器里，容器内没有 systemd，也没有宿主机的 nginx 进程。所以续期脚本的设计是
「续完回到宿主机，无条件 reload 一次」——`nginx -s reload` 是平滑重载，配置没变时开销可以忽略。

## 六、日常操作

```bash
# 看证书还剩多久（证书目录名就是签发时的 --cert-name，也就是 IP）
openssl x509 -in /etc/letsencrypt/live/<你的服务器公网IP>/cert.pem -noout -dates

# 手动续一次（脚本内部会 reload）
/opt/app/renew-cert.sh

# 看续期历史
tail -50 /var/log/rag-kb-cert-renew.log

# 强制立刻重签（debug 用，会消耗 LE 的签发额度，一天最多 5 次）
docker run --rm -v /etc/letsencrypt:/etc/letsencrypt \
  -v /var/lib/letsencrypt:/var/lib/letsencrypt \
  -v /var/log/letsencrypt:/var/log/letsencrypt \
  -v /var/www/acme:/var/www/acme \
  certbot/certbot renew --force-renewal
systemctl reload nginx

# 验证证书链（不做任何跳过）
curl -v https://<你的服务器公网IP>/api/site 2>&1 | grep -E 'SSL certificate verify|expire date'
```

## 七、出问题怎么查

| 现象 | 先看哪里 |
|---|---|
| 浏览器报证书错误 | `nginx -t` / `systemctl status nginx` / `openssl x509 -in ... -noout -dates` |
| 续期失败 | `/var/log/letsencrypt/letsencrypt.log`，最常见是 80 端口被安全组关了 |
| 签证书时一直「验证失败」 | 80 端口没放行，或者 nginx 默认站点把 `/.well-known/` 接走了（见 5.1） |
| 网站 502 | `systemctl is-active rag-kb`，nginx 只是转发，502 = 后端没起来 |
| 所有访客 IP 都是 127.0.0.1 | nginx 的 XFF 配置被改成追加模式了，或者 `rag.visit.trust-proxy` 被关 |
| SSE 卡住不吐字 | nginx 的 `proxy_buffering` 被打开 |

## 八、如果以后买了域名

1. 域名解析 A 记录指向服务器公网 IP
2. 备案（国内服务器必需，2–3 周）
3. `install-https.sh` 里把 `--ip-address ${SERVER_IP}` 改成 `-d ${DOMAIN}`，`--cert-name` 同步改成域名
4. 去掉 `--preferred-profile shortlived`（那是 IP 证书专用）
5. 重跑 `install-https.sh`，然后 `certbot delete --cert-name <旧的 IP> ` 删掉旧的 IP 证书
6. 把 HSTS 的 `max-age` 从 300 调到 31536000

## 九、安全边界（重要）

加 nginx 之后 **8080 必须只监听在本机**，不能让公网绕过 nginx 直接访问。

这一点落在代码里，不只靠安全组：

```yaml
server:
  address: 127.0.0.1   # 只监听回环
  port: 8080
```

为什么不能只靠安全组：安全组是控制台里的状态，换机器、重建规则、或者哪次排障时
临时放开一下忘了收，就可能把 8080 暴露出去。写在 `application.yml` 里，它跟着代码走，
review 时看得见，换台机器也是默认安全的。

实测端口状态：
```
80     OPEN      nginx
443    OPEN      nginx
8080   CLOSED    公网连不上（127.0.0.1 上 java 正常监听）
```

如果哪天 nginx 挂了、需要直连排查：
```bash
# 在服务器上从本机访问（推荐，不用改任何东西）
curl http://127.0.0.1:8080/actuator/health

# 或者临时把 server.address 注释掉重启（记得改回来）
```

### 9.1 顺手加上的两个配套改动

`application.yml` 还加了：

```yaml
server:
  forward-headers-strategy: native
```

让 Tomcat 认 `X-Forwarded-Proto`，`request.getScheme()` 会变成 `https`。
现在代码里没有生成绝对 URL 的地方，但以后加「分享链接」「回调地址」之类功能时，
不加这一行就会生成 `http://` 的地址，在 HTTPS 页面上被浏览器当成混合内容拦掉。

另外 `rag.visit.trust-proxy` 的注释同步改了 —— 前面有 nginx 之后它**必须为 true**，
否则所有访客都会被记成 127.0.0.1，按 IP 限流全站共用一个额度。
`ClientIpResolver.java` 的类注释也写清了信任链是「nginx 覆盖 XFF → 后端取第一段」，
以及两个前提条件（nginx 是覆盖模式、8080 不对外）。
