package com.gameplatform.hostaccess;

import org.apache.sshd.sftp.client.SftpClient;

/** 在池化 SSH 会话上执行一次 SFTP 操作。 */
@FunctionalInterface
public interface SftpOperation<T> {
    T execute(SftpClient sftp) throws Exception;
}
