package com.example.kb.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件存储抽象。
 *
 * <p>两个实现：</p>
 * <ul>
 *   <li>{@link LocalStorage} —— 存服务器本地磁盘，返回 {@code /media/xxx}</li>
 *   <li>{@link OssStorage} —— 存阿里云 OSS，返回完整 https URL</li>
 * </ul>
 *
 * <p>⚠️ 为什么不直接写 OSS 调用：本地要能跑起来调试（OSS 凭据不是随时有，
 * 而且本地传文件走网络会慢）。抽一层之后 {@code rag.storage.type=local|oss}
 * 一行配置切换，业务代码不知道文件到底在哪。</p>
 *
 * <p>⚠️ 这层<b>只负责存和删</b>，不负责权限判断、不负责生成缩略图。
 * 谁可以传、传什么格式是 Service 的事；图片压缩将来要做也不该塞这里。</p>
 */
public interface FileStorage {

    /**
     * 存一个文件。
     *
     * @param key         存储路径，形如 {@code photos/2026/abc123.jpg}。
     *                    ⚠️ 由调用方生成（含随机名），不要让用户文件名直接当 key
     * @param in          内容流（调用方负责关闭）
     * @param size        字节数（OSS 需要，-1 表示未知）
     * @param contentType MIME，如 {@code image/jpeg}；用于 OSS 的 Content-Type 头
     * @return 可直接放进 {@code <img src>} 的 URL
     * @throws IOException 读流失败
     */
    String put(String key, InputStream in, long size, String contentType) throws IOException;

    /**
     * 删一个文件。文件不存在时静默返回（幂等），不抛异常。
     *
     * <p>⚠️ 故意不抛「文件不存在」：删一张已经被手工删掉的图，
     * 不该让后台报错让人以为出问题了。</p>
     */
    void delete(String key);

    /**
     * 把存储 URL 反解成 key，用于删除时找回原 key。
     *
     * @return 认不出来时返回 null（比如文件是别处传来的外链，就不该删）
     */
    String keyOf(String url);

    /** 存储类型名，只用于启动日志和排查 */
    String type();
}
