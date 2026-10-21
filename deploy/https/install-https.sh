#!/usr/bin/env bash
# install-https.sh —— 给这台没有域名的服务器装上 HTTPS（Let's Encrypt IP 证书）
#
# 用法（在服务器上以 root 执行）：
#   bash install-https.sh --ip 203.0.113.10              # 正式签发
#   bash install-https.sh --ip 203.0.113.10 --staging    # 用 Let's Encrypt 测试环境签，不消耗正式额度
#   bash install-https.sh --ip 203.0.113.10 --dry-run    # 只打印要做的事，不改任何文件
#
# 其它参数：
#   --app-dir <目录>   应用所在目录，默认 /opt/app（续期脚本和 cron 会用到）
#
# 它做的事（每一步都可重复执行）：
#   1. 检查前置条件（root / 端口 80 可达 / docker 可用）
#   2. 装 nginx（CentOS 8 AppStream 里的 1.14.1）
#   3. 建 ACME webroot 目录 /var/www/acme
#   4. 先用「只有 80」的引导配置起 nginx
#   5. certbot（docker）走 webroot 模式签 IP 证书
#   6. 换成正式的 nginx.conf + conf.d/rag-kb.conf（80 跳 443、443 反代 8080）
#   7. 装每天跑的续期 cron
#
# 为什么必须是 IP 证书：这台机器没有域名。Let's Encrypt 从 2026-01-15 起
# 支持给 IP 地址签证书（profile=shortlived），有效期 160 小时。
#
# 为什么一定要 nginx：Tomcat 只能在启动时读一次证书，续期后必须重启整个
# 应用；IP 证书每 4 天续一次 = 每 4 天重启一次站点。加 nginx 之后证书由
# nginx 持有，续期只要 `nginx -s reload`，Java 进程一次都不用重启。
set -euo pipefail

# ---------------- 可调参数 ----------------
# 服务器公网 IP：证书就是签给这个地址的，必须填对。
# 三种给法，优先级从高到低：
#   1. 命令行  --ip 203.0.113.10
#   2. 环境变量 SERVER_IP=203.0.113.10
#   3. 脚本自动探测（读默认路由那条网卡的地址）
# ⚠️ 云服务器上自动探测通常能拿到私网地址；要签的是公网 IP，
#    所以云上请显式传 --ip，别指望自动探测。
SERVER_IP="${SERVER_IP:-}"
ACME_WEBROOT="/var/www/acme"
LE_DIR="/etc/letsencrypt"
APP_DIR="/opt/app"
APP_PORT="8080"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STAGING=""
DRY_RUN=""
while [ $# -gt 0 ]; do
    case "$1" in
        --staging) STAGING="--staging" ;;
        --dry-run) DRY_RUN="1" ;;
        --ip) shift; SERVER_IP="${1:-}" ;;
        --app-dir) shift; APP_DIR="${1:-}" ;;
        *) echo "未知参数：$1" >&2; exit 1 ;;
    esac
    shift
done

if [ -z "${SERVER_IP}" ]; then
    # `ip route get 1.1.1.1` 输出里 src 后面就是本机出口地址；
    # 老系统没有 ip 命令时退回 hostname -I 的第一个。
    SERVER_IP="$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+' | head -1)"
    [ -n "${SERVER_IP}" ] || SERVER_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
[ -n "${SERVER_IP}" ] || { echo "无法确定服务器 IP，请用 --ip 指定" >&2; exit 1; }

log() { echo "[install-https] $*"; }
die() { echo "[install-https] 错误：$*" >&2; exit 1; }
run() {
    if [ -n "${DRY_RUN}" ]; then
        echo "  [dry-run] $*"
    else
        eval "$@"
    fi
}

# ---------------- 1. 前置检查 ----------------
log "检查前置条件"
[ "$(id -u)" -eq 0 ] || die "必须用 root 跑"
command -v docker >/dev/null || die "没有 docker"
docker info >/dev/null 2>&1 || die "docker 守护进程没在跑"

# 端口 80 必须能从公网进来，否则 ACME HTTP-01 校验必然失败。
# 用 Let's Encrypt 的角度测不了（它不公布校验 IP 段），退而求其次：
# 检查本机 80 有没有人监听 + 安全组是否放行（靠 curl 一个外部回显服务判断太绕，
# 这里只提示，真正验证留给 certbot 报错）。
log "SERVER_IP=${SERVER_IP}  ACME_WEBROOT=${ACME_WEBROOT}  APP_PORT=${APP_PORT}"

# ---------------- 2. 装 nginx ----------------
if rpm -q nginx >/dev/null 2>&1; then
    log "nginx 已安装：$(nginx -v 2>&1)"
else
    log "安装 nginx"
    run "dnf install -y nginx"
fi

# ---------------- 3. ACME webroot ----------------
log "准备 ACME 校验目录 ${ACME_WEBROOT}/.well-known/acme-challenge"
run "mkdir -p ${ACME_WEBROOT}/.well-known/acme-challenge"
# nginx 以 nginx 用户跑，要能读到校验文件
run "chown -R nginx:nginx ${ACME_WEBROOT}"

# ---------------- 4. 引导配置（只有 80） ----------------
# 第一次签证书时正式配置还不能用（引用的证书文件不存在），
# 所以先放一份只有 80 的配置把 nginx 拉起来，让 ACME 校验有东西应答。
CERT_FILE="${LE_DIR}/live/${SERVER_IP}/fullchain.pem"
if [ ! -f "${CERT_FILE}" ]; then
    log "证书还不存在，先装引导配置（只有 80 端口）"
    [ -f /etc/nginx/nginx.conf.rag-orig ] || run "cp -a /etc/nginx/nginx.conf /etc/nginx/nginx.conf.rag-orig"
    run "cp -f ${SCRIPT_DIR}/nginx-bootstrap.conf /etc/nginx/nginx.conf"
    # 引导阶段的 conf.d 里不能有正式站点配置，否则还是引用不存在的证书
    run "rm -f /etc/nginx/conf.d/rag-kb.conf"
    run "nginx -t"
    run "systemctl enable --now nginx"
    run "systemctl reload nginx || systemctl restart nginx"
    log "引导配置已生效，80 端口可以应答 ACME 校验"
else
    log "证书已存在，跳过引导配置"
fi

# ---------------- 5. 签证书 ----------------
if [ -f "${CERT_FILE}" ] && [ -z "${STAGING}" ]; then
    log "证书已存在：${CERT_FILE}，跳过签发（要重签请先 certbot delete --cert-name ${SERVER_IP}）"
else
    log "开始签发证书（staging=${STAGING:-否}）"
    # --preferred-profile shortlived 是 IP 证书的硬性要求：
    # 默认的 classic profile 只签域名，IP 证书必须走 shortlived（160 小时）。
    # --webroot 而不是 --standalone：standalone 会自己占 80 端口，跟 nginx 打架。
    # --register-unsafely-without-email：不想收 LE 的邮件；真要出问题，
    #   证书到期前 cron 里的剩余天数告警会发现。
    run "docker run --rm \
        -v ${LE_DIR}:/etc/letsencrypt \
        -v /var/lib/letsencrypt:/var/lib/letsencrypt \
        -v /var/log/letsencrypt:/var/log/letsencrypt \
        -v ${ACME_WEBROOT}:/var/www/acme \
        certbot/certbot certonly \
            ${STAGING} \
            --non-interactive \
            --agree-tos \
            --register-unsafely-without-email \
            --preferred-profile shortlived \
            --webroot --webroot-path /var/www/acme \
            --ip-address ${SERVER_IP} \
            --cert-name ${SERVER_IP}"

    [ -n "${DRY_RUN}" ] || [ -f "${CERT_FILE}" ] || die "签发失败：${CERT_FILE} 不存在"
fi

# ---------------- 6. 正式配置（80 跳 443 + 443 反代） ----------------
log "安装正式 nginx 配置"
[ -n "${DRY_RUN}" ] || {
    sed "s/__SERVER_IP__/${SERVER_IP}/g" "${SCRIPT_DIR}/nginx.conf" > /etc/nginx/nginx.conf
    sed "s/__SERVER_IP__/${SERVER_IP}/g" "${SCRIPT_DIR}/rag-kb.conf" > /etc/nginx/conf.d/rag-kb.conf
}
run "nginx -t"
run "systemctl enable nginx"
run "systemctl reload nginx"

# ---------------- 7. 续期 cron ----------------
log "安装续期定时任务"
# 幂等：从仓库目录跑时 source 和 dest 不同，正常拷贝；
# 直接在 ${APP_DIR} 目录里跑时两者相同，`install` 会报
# "are the same file"，所以先判断一下。
if [ "$(readlink -f "${SCRIPT_DIR}/renew-cert.sh")" != "$(readlink -f ${APP_DIR}/renew-cert.sh 2>/dev/null || echo '')" ]; then
    run "install -m 0755 ${SCRIPT_DIR}/renew-cert.sh ${APP_DIR}/renew-cert.sh"
else
    run "chmod 0755 ${APP_DIR}/renew-cert.sh"
fi
# 放在 /etc/cron.d 而不是 root 的 crontab：/etc/cron.d 里的文件带用户名列，
# 便于一眼看出跑在哪个用户下，也不会被 `crontab -r` 误删。
# 时间挑 03:17：避开整点的集中调度（很多任务都写 3:00），
# 服务器本身也在凌晨最闲。
[ -n "${DRY_RUN}" ] || cat > /etc/cron.d/rag-kb-cert-renew <<CRONEOF
# Let's Encrypt IP 证书续期（160 小时有效期，certbot 在半衰期约 3.3 天时自动续）
# 每天跑一次，绝大多数时候是空转，真正续期大约每 4 天一次。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
17 3 * * * root ${APP_DIR}/renew-cert.sh >> /var/log/rag-kb-cert-renew.log 2>&1
CRONEOF
run "chmod 0644 /etc/cron.d/rag-kb-cert-renew"

log "完成"
log "访问地址：https://${SERVER_IP}/"
log "证书路径：${CERT_FILE}"
log "续期日志：/var/log/rag-kb-cert-renew.log"
log "手动续期：${APP_DIR}/renew-cert.sh"
