package com.example.kb.infrastructure.geo;

import com.example.kb.domain.KbVisit;
import com.example.kb.repository.VisitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
/**
 * IP 归属地异步补全。
 *
 * <p>为什么不放在访问链路里同步查：查归属地要调外部 HTTP 接口，
 * 快则 100ms、慢则超时 5s。放同步链路会让别人打开你主页时卡住 ——
 * 这是「记录访客」的成本，不该由访客承担。所以：同步只写 IP+时间（几十毫秒），
 * 归属地交给后台每分钟补一次。</p>
 *
 * <p>接口降级链（服务器上实测结果）：</p>
 * <ol>
 *   <li><b>ip-api.com</b> —— ❌ 在国内云服务器上超时（http 和 https 都是 000），不用</li>
 *   <li><b>ip.sb</b> —— ✅ 200，字段全（country/region/city/isp/asn）</li>
 *   <li><b>ipinfo.io</b> —— ✅ 200，但城市名是英文（Hong Kong）</li>
 *   <li><b>ipapi.co</b> —— ✅ 200，免费额度 1000 次/天</li>
 * </ol>
 * 优先 ip.sb（字段最全）→ 失败退 ipinfo.io。ipapi.co 留作最后兜底。
 *
 * <p>⚠️ 免费接口有频率限制。这里每分钟最多处理 {@value #BATCH} 条，
 * 且失败后同一行标记 FAILED 不再重试，避免把额度刷爆。</p>
 */
@Service
public class GeoIpService {

    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);

    /** 每轮最多补多少条 —— 免费接口限流下别贪多 */
    private static final int BATCH = 20;

    /** 单次请求超时。归属地是「锦上添花」，不值得为它等 5 秒 */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final VisitRepository visitRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeoIpService(VisitRepository visitRepository, ObjectMapper objectMapper) {
        this.visitRepository = visitRepository;
        this.objectMapper = objectMapper;
        // ⚠️ 必须显式设 connectTimeout：默认是无限等待，DNS 卡住会占死线程
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 每分钟跑一次，补全还没查到归属地的记录。
     *
     * <p>⚠️ 这是单机方案。将来多实例部署要换成分片任务或消息队列，
     * 否则两个实例会重复查同一批 IP（结果正确但浪费免费额度）。</p>
     */
    @Scheduled(fixedDelayString = "${rag.visit.geo-delay-ms:60000}",
            initialDelayString = "${rag.visit.geo-initial-delay-ms:20000}")
    public void enrichPending() {
        List<KbVisit> pending = visitRepository.findPendingGeo(BATCH);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("开始补全 {} 条访问记录的归属地", pending.size());
        int ok = 0;
        for (KbVisit visit : pending) {
            Geo geo = lookup(visit.getIp());
            if (geo == null) {
                visitRepository.markGeoFailed(visit.getId());
            } else {
                visitRepository.updateGeo(visit.getId(), geo.country, geo.region, geo.city, geo.isp);
                ok++;
            }
        }
        log.info("归属地补全完成：成功 {} / 共 {}", ok, pending.size());
    }

    /** 归属地查询结果 */
    public record Geo(String country, String region, String city, String isp) {
    }

    /**
     * 查一个 IP 的归属地，多接口降级。
     *
     * @return 查不到（内网 IP、接口全挂、解析失败）返回 null
     */
    public Geo lookup(String ip) {
        if (ip == null || ip.isBlank() || isPrivate(ip)) {
            // 内网 IP 查不到也没意义，直接算失败，别浪费接口额度
            return null;
        }
        Geo geo = lookupIpSb(ip);
        if (geo == null) {
            geo = lookupIpinfo(ip);
        }
        return geo;
    }

    /** 首选：ip.sb —— 字段全、中文友好度一般但信息最完整 */
    private Geo lookupIpSb(String ip) {
        JsonNode root = getJson("https://api.ip.sb/geoip/" + ip);
        if (root == null) {
            return null;
        }
        String country = text(root, "country");
        // ip.sb 不返回中文国名，转一下常见值让后台列表好读
        country = zhCountry(country);
        return new Geo(country, text(root, "region"), text(root, "city"),
                firstNonBlank(text(root, "isp"), text(root, "organization")));
    }

    /** 备用：ipinfo.io —— 稳定但城市是英文，免费额度 5 万次/月 */
    private Geo lookupIpinfo(String ip) {
        JsonNode root = getJson("https://ipinfo.io/" + ip + "/json");
        if (root == null) {
            return null;
        }
        // ipinfo 的字段：country 是国家代码（CN/HK），city/region 是英文
        String country = zhCountry(text(root, "country"));
        return new Geo(country, text(root, "region"), text(root, "city"),
                text(root, "org"));
    }

    /** 发 GET 拿 JSON。任何异常（超时、4xx、非 JSON）都返回 null，由调用方降级 */
    private JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "rag-kb/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("归属地接口 {} 返回 {}", url, resp.statusCode());
                return null;
            }
            String body = resp.body();
            if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
                return null;
            }
            return objectMapper.readTree(body);
        } catch (InterruptedException e) {
            // 恢复中断标记，别把线程池的中断信号吞掉
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("归属地接口 {} 失败：{}", url, e.toString());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /**
     * 国家代码转中文名。ip.sb 返回英文全名、ipinfo 返回两位代码，
     * 两种都可能进来，所以这里两种都认。
     */
    private static String zhCountry(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toUpperCase()) {
            case "CN", "CHINA" -> "中国";
            case "HK", "HONG KONG" -> "中国香港";
            case "TW", "TAIWAN" -> "中国台湾";
            case "MO", "MACAU", "MACAO" -> "中国澳门";
            case "JP", "JAPAN" -> "日本";
            case "KR", "SOUTH KOREA", "KOREA" -> "韩国";
            case "SG", "SINGAPORE" -> "新加坡";
            case "US", "UNITED STATES" -> "美国";
            case "GB", "UNITED KINGDOM" -> "英国";
            case "DE", "GERMANY" -> "德国";
            case "FR", "FRANCE" -> "法国";
            case "RU", "RUSSIA" -> "俄罗斯";
            case "CA", "CANADA" -> "加拿大";
            case "AU", "AUSTRALIA" -> "澳大利亚";
            case "IN", "INDIA" -> "印度";
            case "NL", "NETHERLANDS" -> "荷兰";
            default -> raw;
        };
    }

    /**
     * 判断是否内网/保留地址。
     *
     * <p>内网 IP 查归属地没有意义（会返回空或错误），而且我们服务部署在
     * Docker + 可能的反向代理后面，拿到的可能是 172.17.x.x 这种容器地址 ——
     * 那种情况下真实客户端 IP 要从 X-Forwarded-For 取，见 VisitFilter。</p>
     */
    static boolean isPrivate(String ip) {
        if (ip == null) {
            return true;
        }
        if ("127.0.0.1".equals(ip) || "0.0.0.0".equals(ip) || "::1".equals(ip) || "localhost".equals(ip)) {
            return true;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            // IPv6 或其他格式，交给接口自己判断
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10                                  // 10.0.0.0/8
                    || (a == 172 && b >= 16 && b <= 31)     // 172.16.0.0/12
                    || (a == 192 && b == 168)               // 192.168.0.0/16
                    || (a == 169 && b == 254);              // 169.254.0.0/16 链路本地
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
