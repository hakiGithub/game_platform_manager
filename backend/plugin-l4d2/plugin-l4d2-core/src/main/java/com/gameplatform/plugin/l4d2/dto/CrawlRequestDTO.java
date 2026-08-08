package com.gameplatform.plugin.l4d2.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 触发爬取请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CrawlRequestDTO {

    /** FULL 或 INCREMENTAL */
    @NotBlank
    private String type;
}
