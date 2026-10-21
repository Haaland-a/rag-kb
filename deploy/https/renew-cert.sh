#!/usr/bin/env bash
# renew-cert.sh —— 续期 Let's Encrypt 的 IP 证书，续完重载 nginx
#
# 谁调它：/etc/cron.d/rag-kb-cert-renew，每天凌晨 3 点一次
# 为什么每天跑：IP 证书只有 160 小时（6.67 天）。certbot 对有效期短于 10 天的
#   证书，会在「半衰期」（约 80 小时 / 3.3 天）就判定需要续期，所以每天跑一次，
#   实际大约每 4 天真正续一次，剩下几次是空转（几秒钟）。
#
# 为什么不用 certbot 的 --deploy-hook 直接 reload nginx：
#   certbot 跑在容器里，容器内没有 systemd，也没有宿主机的 nginx 进程。
#   最省事又可靠的做法是「续完回到宿主机，无条件 reload 一次」——
#   nginx -s reload 是平滑重载，配置没变时开销可以忽略，天天 reload 也没关系。
set -uo pipefail

LOG_TAG="[rag-kb-cert-renew]"
LE_DIR="/etc/letsencrypt"
CERT=""

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') ${LOG_TAG} $*"; }

# 证书目录名就是签发时 --cert-name 的值（install-https.sh 用的是 IP）。
# 这里自动找：live/ 下正常只有一个 lineage，多于一个时优先取叫得出 IP 形状的那个。
SERVER_IP="${SERVER_IP:-}"
if [ -z "${SERVER_IP}" ]; then
    for d in "${LE_DIR}"/live/*/; do
        name="$(basename "${d}")"
        [ -f "${d}fullchain.pem" ] || continue
        if [ -z "${CERT}" ]; then
            CERT="${d}fullchain.pem"
            SERVER_IP="${name}"
        elif echo "${name}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
            CERT="${d}fullchain.pem"
            SERVER_IP="${name}"
        fi
    done
else
    CERT="${LE_DIR}/live/${SERVER_IP}/fullchain.pem"
fi

log "开始检查证书续期"

if [ -z "${CERT}" ] || [ ! -f "${CERT}" ]; then
    log "错误：在 ${LE_DIR}/live/ 下没找到证书，请先跑 install-https.sh"
    exit 1
fi
log "证书：${CERT}"

# 续期前先记一下到期时间，方便事后对比
BEFORE_END=$(openssl x509 -in "${CERT}" -noout -enddate 2>/dev/null | cut -d= -f2)
log "当前证书到期时间：${BEFORE_END}"

# certbot renew 会对所有 lineage 判断是否需要续期，不需要的直接跳过。
# --quiet 只输出错误；失败时退出码非 0。
docker run --rm \
    -v "${LE_DIR}:/etc/letsencrypt" \
    -v /var/lib/letsencrypt:/var/lib/letsencrypt \
    -v /var/log/letsencrypt:/var/log/letsencrypt \
    -v /var/www/acme:/var/www/acme \
    certbot/certbot renew --quiet --non-interactive
RC=$?

if [ ${RC} -ne 0 ]; then
    log "错误：certbot renew 失败，退出码 ${RC}。证书未更新，站点仍在用旧证书。"
    exit ${RC}
fi

AFTER_END=$(openssl x509 -in "${CERT}" -noout -enddate 2>/dev/null | cut -d= -f2)
if [ "${BEFORE_END}" = "${AFTER_END}" ]; then
    log "本次未续期（还没到时间），到期时间不变：${AFTER_END}"
else
    log "已续期：${BEFORE_END} -> ${AFTER_END}"
fi

# 无条件 reload：续没续都重载，保证 nginx 一定读到最新的证书文件。
# 先 nginx -t 再 reload，配置被改坏时宁可继续用内存里的旧配置，也不要启动失败。
if nginx -t 2>/dev/null; then
    systemctl reload nginx
    log "nginx 已重载"
else
    log "错误：nginx 配置检查不通过，跳过重载。请手动执行 nginx -t 查看原因。"
    exit 1
fi

# 剩余天数低于 2 天时大声报警 —— 说明续期连续失败了，得人工介入。
# （正常情况续期会把剩余天数拉回 6 天多，不会走到这里）
REMAIN_SEC=$(( $(date -d "${AFTER_END}" +%s) - $(date +%s) ))
REMAIN_DAY=$(( REMAIN_SEC / 86400 ))
if [ ${REMAIN_DAY} -lt 2 ]; then
    log "严重：证书只剩 ${REMAIN_DAY} 天，续期可能一直失败，请检查 /var/log/letsencrypt/"
    exit 2
fi

log "完成，证书剩余约 ${REMAIN_DAY} 天"
