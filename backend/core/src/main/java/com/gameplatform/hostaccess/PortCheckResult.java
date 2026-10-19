package com.gameplatform.hostaccess;

import lombok.Data;

/** 主机端口占用检查结果。 */
@Data
public class PortCheckResult {
    private int port;
    private boolean available;
    private String usedBy;
    private String error;
}
