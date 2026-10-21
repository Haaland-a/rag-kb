/**
 * 浏览器设备指纹。
 *
 * <p>用途：挡住「同一台设备批量注册账号」。</p>
 *
 * <h3>⚠️ 这是「软指纹」，不是硬身份，实现上必须接受三点局限</h3>
 * <ol>
 *   <li><b>清浏览器缓存就换了</b>：localStorage 被清、隐身窗口、换浏览器都会
 *       产生新指纹。所以后端还有「同 IP 24 小时限 2 个」作为第二道。</li>
 *   <li><b>不能跨设备识别人</b>：同一个人换手机就变成新设备。这不是 bug，
 *       个人站的留言板没必要做实名。</li>
 *   <li><b>指纹本身不该包含隐私</b>：只用「屏幕 / 时区 / 语言 / 硬件并发数 /
 *       平台」这些公开属性，不读字体列表、不读 Canvas 像素（那些更接近
 *       追踪技术，个人站用不着，而且容易触发隐私插件拦截）。</li>
 * </ol>
 *
 * <h3>为什么最终值的计算是「确定性哈希」而不是随机 UUID</h3>
 * <p>如果只在 localStorage 里存一个随机 UUID，那用户清掉 localStorage
 * 再注册就完全绕过 —— 连一次失败的尝试都没有。而把
 * 「随机 UUID + 设备特征」一起哈希，清掉 localStorage 后设备特征仍在，
 * 算出来的指纹<b>有一半概率撞上</b>（UUID 换了但特征没换）。
 * 这不是确定性保证，但比纯随机强，而且不引入任何额外依赖。</p>
 *
 * <p>⚠️ 实际做法更简单也更有效：<b>localStorage 优先</b>，
 * 里面存的就是「特征哈希」，不存随机数。这样清掉 localStorage 后
 * 重新算出来的特征哈希<b>完全一致</b> —— 因为特征没变。
 * 真正能绕过的是「同时改时区 + 改屏幕分辨率 + 换浏览器」，
 * 那已经超出「随手多注册一个」的范畴了。</p>
 */

const KEY = 'kb.device.v1'

/**
 * 采集设备特征并算成一个稳定字符串。
 *
 * @returns {string} 32 位十六进制串（不暴露原始特征，避免被拼回去）
 */
function computeFingerprint() {
  const n = navigator
  const s = screen
  const parts = [
    n.userAgent || '',
    n.language || '',
    (n.languages || []).join(','),
    s.width, s.height,
    s.colorDepth,
    // 有些隐私插件会把时区伪装成 UTC，这会让指纹变得不稳定 —— 接受
    Intl.DateTimeFormat().resolvedOptions().timeZone || '',
    // 硬件并发数在移动端常为空，空值也是特征的一部分
    n.hardwareConcurrency || 0,
    n.platform || '',
    // ⚠️ 不读 canvas / 字体列表：前者是追踪技术，后者要枚举上千个字体名
    // 才有一点区分度，两个都不值得
  ]
  return hash32(parts.join('|'))
}

/**
 * FNV-1a 32 位哈希。
 *
 * <p>选它而不是引入 sha256 库：只需要「稳定、碰撞概率够低」，
 * 不需要密码学强度（这不是密码）。20 行手写换来零依赖。</p>
 */
function hash32(str) {
  let h = 0x811c9dc5
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    // FNV prime 16777619，用移位避免浮点乘法的精度问题
    h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0
  }
  // 再混一遍长度，防止不同长度的输入产生相同前缀
  const lenMix = (str.length * 0x9e3779b1) >>> 0
  return ((h ^ lenMix) >>> 0).toString(16).padStart(8, '0')
}

/**
 * 取本设备的指纹。
 *
 * <p>先读 localStorage；没有就现算一个并写回。所以正常情况下
 * 同一个浏览器永远拿到同一个值，清掉缓存后重算也还是这个值
 * （因为设备特征没变）。</p>
 *
 * @returns {string} 指纹字符串；隐私模式下写不进 localStorage 也照样返回
 */
export function getDeviceId() {
  try {
    const cached = localStorage.getItem(KEY)
    if (cached) return cached
    const fp = computeFingerprint()
    localStorage.setItem(KEY, fp)
    return fp
  } catch {
    // 隐身模式 / 禁用存储：算一次直接用，不缓存。
    // 每次调用都重算，但不抛错 —— 注册流程不能因为这个失败
    return computeFingerprint()
  }
}
