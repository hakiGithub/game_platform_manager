package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 基于 ip2region xdb 的 GeoIP 查询服务。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class GeoIpService {

    private final L4D2Config config;
    private Searcher searcher;
    private byte[] cbuf;

    public GeoIpService(L4D2Config config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        try {
            String path = config.getGeoip().getXdbPath();
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                cbuf = is.readAllBytes();
            }
            searcher = Searcher.newWithBuffer(cbuf);
            log.info("GeoIpService initialized with xdb: {} ({} bytes)", path, cbuf.length);
        } catch (Exception e) {
            log.warn("GeoIpService init failed, GeoIP query will return 'unknown': {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (searcher != null) {
            try { searcher.close(); } catch (Exception ignored) {}
        }
    }

    /** 查询 IP 归属地，格式：国家|区域|省份|城市|ISP */
    public String query(String ip) {
        if (searcher == null || ip == null) return "unknown";
        try {
            String region = searcher.search(ip);
            return region != null ? region : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 仅返回省份 */
    public String queryProvince(String ip) {
        String full = query(ip);
        String[] parts = full.split("\\|");
        if (parts.length >= 4) return parts[3];
        return "unknown";
    }
}
