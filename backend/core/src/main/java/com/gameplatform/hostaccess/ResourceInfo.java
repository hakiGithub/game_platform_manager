package com.gameplatform.hostaccess;

import lombok.Data;

/** 主机资源快照。不可达时由 {@link RemoteHost#getResourceInfo(Long)} 抛出异常。 */
@Data
public class ResourceInfo {
    private boolean success;
    private Integer cpuCores;
    private Double cpuUsage;
    private String cpuModel;
    private Long memoryTotal;
    private Long memoryUsed;
    private Long memoryFree;
    private Double memoryUsage;
    private Long diskTotal;
    private Long diskUsed;
    private Long diskFree;
    private Double diskUsage;
    private Long networkRxBytes;
    private Long networkTxBytes;
    private String error;
}
