package com.gameplatform.util;

import com.gameplatform.plugin.service.FileTransferProgressCallback;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件传输进度辅助工具：提供带进度计数的流包装与回调生命周期触发。
 *
 * <p>计数流基于 {@link FilterInputStream}/{@link FilterOutputStream} 逐块计数，
 * 不做任何整文件缓冲，内存占用与普通流式传输一致。
 *
 * <p>节流策略：已传输字节较上次回调累计 ≥64KB 或百分比变化 ≥1% 才触发一次
 * {@link FileTransferProgressCallback#onProgress}（totalBytes 未知时仅按 64KB 节流）。
 * 回调异常会直接从流读写中抛出，从而中止传输——见回调接口的"异常即中止"契约。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class TransferProgress {

    /** 进度回调最小触发间隔（字节） */
    private static final long REPORT_CHUNK_BYTES = 64 * 1024;
    /** 进度回调最小百分比变化（百分比点，totalBytes 已知时生效） */
    private static final long REPORT_CHUNK_PERCENT = 1;

    private TransferProgress() {
    }

    /** 触发开始回调（callback 为 null 时无操作） */
    public static void fireStart(FileTransferProgressCallback callback, long totalBytes) {
        if (callback != null) {
            callback.onStart(totalBytes);
        }
    }

    /** 触发完成回调（callback 为 null 时无操作） */
    public static void fireComplete(FileTransferProgressCallback callback) {
        if (callback != null) {
            callback.onComplete();
        }
    }

    /** 触发失败回调（callback 为 null 时无操作；回调自身异常仅记日志，避免吞掉原始异常） */
    public static void fireError(FileTransferProgressCallback callback, Throwable error) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(error);
        } catch (Exception e) {
            // 保持原始异常为主异常，回调自身的失败不能掩盖传输失败
            error.addSuppressed(e);
        }
    }

    /**
     * 包装输入流，按读取字节数节流回调进度（上传方向）。
     */
    public static InputStream counting(InputStream in, long totalBytes,
                                       FileTransferProgressCallback callback) {
        if (callback == null) {
            return in;
        }
        return new CountingInputStream(in, totalBytes, callback);
    }

    /**
     * 包装输出流，按写出字节数节流回调进度（下载方向）。
     */
    public static OutputStream counting(OutputStream out, long totalBytes,
                                        FileTransferProgressCallback callback) {
        if (callback == null) {
            return out;
        }
        return new CountingOutputStream(out, totalBytes, callback);
    }

    /**
     * 进度节流器：按 64KB / 1% 双阈值决定是否触发 onProgress。
     */
    private static final class Throttle {
        private final long totalBytes;
        private final FileTransferProgressCallback callback;
        private long lastReportedBytes;

        private Throttle(long totalBytes, FileTransferProgressCallback callback) {
            this.totalBytes = totalBytes;
            this.callback = callback;
        }

        private void onBytes(long bytesTransferred) {
            if (totalBytes <= 0) {
                // 总量未知：仅按字节阈值节流
                if (bytesTransferred - lastReportedBytes >= REPORT_CHUNK_BYTES) {
                    lastReportedBytes = bytesTransferred;
                    callback.onProgress(bytesTransferred, -1);
                }
                return;
            }
            long lastPercent = lastReportedBytes * 100 / totalBytes;
            long currentPercent = bytesTransferred * 100 / totalBytes;
            if (bytesTransferred - lastReportedBytes >= REPORT_CHUNK_BYTES
                    || currentPercent - lastPercent >= REPORT_CHUNK_PERCENT) {
                lastReportedBytes = bytesTransferred;
                callback.onProgress(bytesTransferred, totalBytes);
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final Throttle throttle;

        private CountingInputStream(InputStream in, long totalBytes,
                                    FileTransferProgressCallback callback) {
            super(in);
            this.throttle = new Throttle(totalBytes, callback);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
                throttle.onBytes(count);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                throttle.onBytes(count);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count += skipped;
                throttle.onBytes(count);
            }
            return skipped;
        }

        private long count;
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private final Throttle throttle;

        private CountingOutputStream(OutputStream out, long totalBytes,
                                     FileTransferProgressCallback callback) {
            super(out);
            this.throttle = new Throttle(totalBytes, callback);
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            count++;
            throttle.onBytes(count);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            count += len;
            throttle.onBytes(count);
        }

        private long count;
    }
}
