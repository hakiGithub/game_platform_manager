package com.gameplatform.clouddrive;

import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.clouddrive.extension.CloudAccountSpec;
import com.haki.clouddrive.core.domain.JobRecord;
import com.haki.clouddrive.core.domain.JobSource;
import com.haki.clouddrive.core.domain.JobStatus;
import com.haki.clouddrive.core.domain.JobType;
import com.haki.clouddrive.core.transfer.TransferRequest;
import com.haki.clouddrive.sdk.CloudDriveClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CloudDriveExecutor 转存请求")
class CloudDriveExecutorTransferTest {

    @Test
    @DisplayName("passcode 原样透传到 SDK TransferRequest，并轮询到完成")
    void passesPasscodeAndPollsToCompletion() {
        CloudAccountService accountService = mock(CloudAccountService.class);
        CloudDriveClient client = mock(CloudDriveClient.class);
        CloudDriveProperties properties = new CloudDriveProperties();
        CloudDriveExecutor executor = new CloudDriveExecutor(accountService, client, properties);

        CloudAccountResource account = new CloudAccountResource();
        account.setName("cloud189-main");
        CloudAccountSpec spec = new CloudAccountSpec();
        spec.setProviderType("cloud189");
        account.setSpec(spec);
        when(accountService.get("cloud189-main")).thenReturn(Optional.of(account));
        when(accountService.mountPath(account)).thenReturn("/cloud189/cloud189-main");
        when(client.submitTransfer(any())).thenReturn("job-1");
        when(client.getJob("job-1")).thenReturn(Optional.of(completedJob()));

        List<String> result = executor.transfer("cloud189-main",
                "https://cloud.189.cn/t/example", "abcd",
                "/maps/orange-807", java.time.Duration.ofSeconds(5), "test-plugin");

        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(client).submitTransfer(request.capture());
        assertThat(request.getValue().shareUrl()).isEqualTo("https://cloud.189.cn/t/example");
        assertThat(request.getValue().passcode()).isEqualTo("abcd");
        assertThat(request.getValue().targetMountPath()).isEqualTo("/cloud189/cloud189-main/maps/orange-807");
        assertThat(result).containsExactly("/maps/orange-807/map.vpk");
    }

    @Test
    @DisplayName("空白 passcode 归一化后交由 SDK 处理")
    void normalizesBlankPasscode() {
        CloudAccountService accountService = mock(CloudAccountService.class);
        CloudDriveClient client = mock(CloudDriveClient.class);
        CloudDriveExecutor executor = new CloudDriveExecutor(accountService, client, new CloudDriveProperties());

        CloudAccountResource account = new CloudAccountResource();
        account.setName("quark-main");
        CloudAccountSpec spec = new CloudAccountSpec();
        spec.setProviderType("quark");
        account.setSpec(spec);
        when(accountService.get("quark-main")).thenReturn(Optional.of(account));
        when(accountService.mountPath(account)).thenReturn("/quark/quark-main");
        when(client.submitTransfer(any())).thenReturn("job-2");
        when(client.getJob("job-2")).thenReturn(Optional.of(completedJob()));

        executor.transfer("quark-main", "https://pan.quark.cn/s/example", "  ",
                "/maps/share-test", java.time.Duration.ofSeconds(5), "test-plugin");

        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(client).submitTransfer(request.capture());
        // SDK 的 TransferRequest 构造器把 null 规范化为空串，空提取码以 SDK 契约表示。
        assertThat(request.getValue().passcode()).isEmpty();
    }

    private static JobRecord completedJob() {
        Instant now = Instant.now();
        return new JobRecord("job", JobType.TRANSFER, JobStatus.COMPLETED, "done", "ok",
                null, List.of("/maps/orange-807/map.vpk"), now, now, JobSource.MANUAL, null);
    }
}
