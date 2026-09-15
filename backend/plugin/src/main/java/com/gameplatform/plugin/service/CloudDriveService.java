package com.gameplatform.plugin.service;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 云盘宿主能力服务（ADR-0024）。
 * <p>
 * 主应用统一管理的多云盘传输层：账号按 name 寻址（管理入口在主前端「云盘账号」页），
 * 默认挂载 {@code /{providerType}/{accountName}/} 由主应用隐式派生，调用方只传
 * 挂载内相对路径。仅提供传输能力；转存后下载到主机等业务编排由插件自行实现。
 * <p>
 * 每次调用自动携带调用方（插件 ID）写入日志审计，来源不可伪造。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface CloudDriveService {

    /**
     * 列目录（当前页）。
     *
     * @param accountName 云盘账号 name
     * @param path        挂载内相对路径（如 {@code /} 或 {@code maps/coop}）
     * @param refresh     true 绕过目录缓存强制刷新
     * @return 文件条目列表
     * @throws com.gameplatform.common.exception.BusinessException 账号不存在、路径无挂载覆盖或网盘请求失败
     */
    List<CloudFileInfo> list(String accountName, String path, boolean refresh);

    /**
     * 解析直链。注意：headers 是直链的一部分，自建 HTTP 客户端取回内容时必须逐项带上。
     *
     * @param accountName 云盘账号 name
     * @param path        挂载内相对路径（须为文件）
     * @return 直链信息（url + 必需请求头 + 有效期时间戳）
     * @throws com.gameplatform.common.exception.BusinessException 账号不存在、路径无挂载覆盖或直链不可用（含原因）
     */
    CloudLink link(String accountName, String path);

    /**
     * 流式下载（自动携带直链必需请求头）。
     *
     * @param accountName 云盘账号 name
     * @param path        挂载内相对路径（须为文件）
     * @param target      下载内容输出目标
     * @return 写入字节数
     * @throws com.gameplatform.common.exception.BusinessException 账号不存在、路径无挂载覆盖或下载失败
     */
    long download(String accountName, String path, OutputStream target);

    /**
     * 同步转存（ADR-0024/0025 契约）：阻塞至转存终态并返回转存产物。
     * <p>
     * 超时抛 BusinessException（消息含 jobId，底层作业可能仍在后台执行，可由业务方决策重试）；
     * 转存失败（分享失效等）同样抛 BusinessException。
     *
     * @param accountName 云盘账号 name（转存目标账号）
     * @param shareUrl    分享链接
     * @param passcode    提取码（null 或空 = 无提取码）
     * @param targetPath  挂载内相对目标目录（如 {@code /maps/orange-807}，目录不存在时由引擎创建）
     * @param timeout     等待上限；为 null 时用主应用配置的默认超时
     * @return 转存产物路径列表（挂载内相对路径；引擎未回填时返回空列表，此时目标目录即产物位置）
     * @throws com.gameplatform.common.exception.BusinessException 账号不存在、超时或转存失败
     */
    List<String> transfer(String accountName, String shareUrl, String passcode,
                          String targetPath, Duration timeout);

    /** 文件条目（各网盘统一抽象） */
    record CloudFileInfo(String name, String path, boolean directory, long size, Long modifiedAtMillis) {
    }

    /** 直链：headers 必须与 url 连带使用 */
    record CloudLink(String url, Map<String, String> headers, Long expiresAtMillis) {
    }
}
