package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Map;

/**
 * 外部 HTTP 客户端封装：下载文件、GET/POST JSON。
 *
 * <p>构造器注入 Spring Boot 自动配置的 {@link RestClient.Builder}，
 * 子容器无该 Bean 时降级为 {@link RestClient#create()}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class ExternalHttpClient {

    private final RestClient restClient;

    public ExternalHttpClient(@Autowired(required = false) RestClient.Builder builder) {
        this.restClient = (builder != null) ? builder.build() : RestClient.create();
    }

    public interface ProgressCallback {
        void onProgress(long downloadedBytes);
    }

    public interface CancelToken {
        boolean isCancelled();
    }

    /**
     * 下载文件到系统临时目录，返回下载后的本地文件。
     */
    public File download(String url, String filename, String referer,
                         ProgressCallback callback, CancelToken cancelToken) {
        try {
            File tempFile = Files.createTempFile("l4d2-dl-", "-" + filename).toFile();
            RestClient.RequestHeadersSpec<?> spec = restClient.get()
                    .uri(URI.create(url));
            if (referer != null) spec.header("Referer", referer);

            spec.exchange((req, res) -> {
                try (InputStream is = res.getBody();
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (cancelToken != null && cancelToken.isCancelled()) {
                            tempFile.delete();
                            throw new L4D2PluginException("BUSINESS", "下载已取消");
                        }
                        fos.write(buf, 0, n);
                        downloaded += n;
                        if (callback != null) callback.onProgress(downloaded);
                    }
                }
                return tempFile;
            });
            return tempFile;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new L4D2PluginException("NETWORK", "下载失败: " + url, e);
        }
    }

    /**
     * 下载文件，失败时按固定 1 秒间隔重试。
     *
     * <p>对齐 l4d2-server-next downloadFileWithRetry：N 次重试，1 秒间隔，支持取消。
     *
     * @param url         下载 URL
     * @param filename    临时文件名前缀
     * @param referer     可选 Referer 头
     * @param callback    进度回调（可空）
     * @param cancelToken 取消令牌（可空）
     * @param retries     重试次数（&gt;=1）
     * @return 下载后的本地文件
     */
    public File downloadWithRetry(String url, String filename, String referer,
                                  ProgressCallback callback, CancelToken cancelToken,
                                  int retries) {
        Exception lastErr = null;
        for (int i = 0; i < retries; i++) {
            if (cancelToken != null && cancelToken.isCancelled()) {
                throw new RuntimeException("下载已取消");
            }
            try {
                return download(url, filename, referer, callback, cancelToken);
            } catch (Exception e) {
                lastErr = e;
                log.warn("下载失败（第 {} 次）url={}, err={}", i + 1, url, e.getMessage());
            }
            if (i < retries - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("下载重试被中断", ie);
                }
            }
        }
        throw new RuntimeException("下载失败（已重试 " + retries + " 次）: " + url
                + (lastErr != null ? ", 最后错误: " + lastErr.getMessage() : ""), lastErr);
    }

    public <T> T getForObject(String url, Class<T> type, Map<String, ?> params) {
        try {
            return restClient.get()
                    .uri(buildUri(url, params))
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            throw new L4D2PluginException("EXTERNAL_API", "GET 请求失败: " + url, e);
        }
    }

    private URI buildUri(String url, Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return URI.create(url);
        }
        org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromUri(URI.create(url));
        params.forEach((k, v) -> builder.queryParam(k, v));
        return builder.build().toUri();
    }

    public <T> T postForObject(String url, Object body, Class<T> type) {
        try {
            return restClient.post()
                    .uri(URI.create(url))
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            throw new L4D2PluginException("EXTERNAL_API", "POST 请求失败: " + url, e);
        }
    }
}
