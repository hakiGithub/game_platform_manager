package com.gameplatform.plugin.service;

/**
 * VPK 有效但非 L4D2 地图（无 mission 信息，ADR-0027）：调用方应记 INVALID 而非 FAILED。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class VpkInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VpkInvalidException(String message) {
        super(message);
    }
}
