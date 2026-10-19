package com.gameplatform.hostaccess;

import lombok.Data;

/**
 * 远程命令执行结果。非零退出码仍是可达主机的正常业务结果。
 */
@Data
public class CommandResult {
    private boolean success;
    private int exitCode;
    private String output = "";
    private String error = "";
}
