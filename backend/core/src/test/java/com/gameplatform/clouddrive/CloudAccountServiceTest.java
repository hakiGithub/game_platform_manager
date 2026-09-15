package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.clouddrive.extension.CloudAccountResource;
import com.gameplatform.clouddrive.extension.CloudAccountSpec;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.haki.clouddrive.core.domain.CloudAccount;
import com.haki.clouddrive.core.domain.Mount;
import com.haki.clouddrive.core.error.CredentialInvalidException;
import com.haki.clouddrive.core.model.QuotaInfo;
import com.haki.clouddrive.sdk.CloudDriveClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 云盘账号服务单元测试（ADR-0024）：表为权威源、引擎同步、失败回滚与状态回写。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class CloudAccountServiceTest {

    @Mock
    private ExtensionClient hostExtensionClient;
    @Mock
    private CloudDriveClient client;

    private CloudAccountService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new CloudAccountService(hostExtensionClient, client, objectMapper);
    }

    private CloudAccountResource resource(String name, String providerType, String settingsJson) {
        CloudAccountSpec spec = new CloudAccountSpec();
        spec.setProviderType(providerType);
        spec.setSettingsCipher(settingsJson == null ? null : settingsJson); // 测试直塞，不走 AesUtil
        CloudAccountResource res = new CloudAccountResource();
        res.setName(name);
        res.setSpec(spec);
        res.setStatus(CloudAccountService.STATUS_HEALTHY);
        return res;
    }

    private CloudAccount sdkAccount(String id, String name) {
        return new CloudAccount(id, name, "cloud189", "{}",
                com.haki.clouddrive.core.domain.HealthStatus.HEALTHY, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    void createDuplicateNameRejected() {
        when(hostExtensionClient.get(CloudAccountResource.class, "dup")).thenReturn(
                Optional.of(resource("dup", "cloud189", null)));
        assertThatThrownBy(() -> service.create("dup", "cloud189", "{}", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createRollsBackTableWhenEngineRejectsCredential() throws Exception {
        when(hostExtensionClient.get(CloudAccountResource.class, "quark1")).thenReturn(Optional.empty());
        when(client.settingsClass("quark")).thenReturn(
                (Class) com.haki.clouddrive.provider.quark.QuarkSettings.class);
        when(client.listAccounts()).thenReturn(List.of());
        when(client.addAccount(anyString(), anyString(), any())).thenThrow(
                new CredentialInvalidException("quark", "401"));

        assertThatThrownBy(() -> service.create(
                "quark1", "quark", "{\"cookie\":\"aVeryLongCookieValue123\",\"rootPath\":\"/\"}", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("凭证无效");

        // 引擎接入失败必须回滚扩展表，不留半状态
        verify(hostExtensionClient).delete(CloudAccountResource.class, "quark1");
    }

    @Test
    void createStoresCipherAndHintAndMountsDefaultPath() throws Exception {
        when(hostExtensionClient.get(CloudAccountResource.class, "quark1")).thenReturn(Optional.empty());
        when(client.settingsClass("quark")).thenReturn(
                (Class) com.haki.clouddrive.provider.quark.QuarkSettings.class);
        when(client.listAccounts()).thenReturn(List.of());
        when(client.addAccount(eq("quark1"), eq("quark"), any())).thenReturn(sdkAccount("a1", "quark1"));
        when(client.listMounts()).thenReturn(List.of());

        service.create("quark1", "quark",
                "{\"cookie\":\"aVeryLongCookieValue123\",\"rootPath\":\"/\"}", "主号", null);

        ArgumentCaptor<CloudAccountResource> captor = ArgumentCaptor.forClass(CloudAccountResource.class);
        verify(hostExtensionClient).create(captor.capture());
        CloudAccountSpec spec = captor.getValue().getSpec();
        // 密文不等于明文；脱敏提示只含尾 4 位
        assertThat(spec.getSettingsCipher()).isNotEqualTo("{\"cookie\":\"aVeryLongCookieValue123\",\"rootPath\":\"/\"}");
        assertThat(spec.getCredentialHint()).isEqualTo("****e123");
        // 默认挂载 /{providerType}/{name}
        verify(client).createMount("a1", "/quark/quark1", "/");
    }

    @Test
    void verifyMarksUnhealthyWhenProbeFails() throws Exception {
        CloudAccountResource res = resource("q1", "cloud189", "{\"ssonCookie\":\"x\",\"rootFolderId\":\"-11\"}");
        when(hostExtensionClient.get(CloudAccountResource.class, "q1")).thenReturn(Optional.of(res));
        when(client.settingsClass("cloud189")).thenReturn(
                (Class) com.haki.clouddrive.provider.cloud189.Cloud189Settings.class);
        when(client.listAccounts()).thenReturn(List.of(sdkAccount("a1", "q1")));
        when(client.replaceSettings(eq("a1"), any())).thenReturn(sdkAccount("a1", "q1"));
        when(client.getAccount("a1")).thenReturn(Optional.of(sdkAccount("a1", "q1")));
        when(client.verifyAccount("a1")).thenThrow(new CredentialInvalidException("cloud189", "x"));

        assertThatThrownBy(() -> service.verify("q1")).isInstanceOf(BusinessException.class);
        verify(hostExtensionClient).updateStatus(CloudAccountResource.class, "q1",
                CloudAccountService.STATUS_UNHEALTHY);
    }

    @Test
    void verifyMarksUnhealthyWhenSdkReportsUnhealthy() throws Exception {
        // SDK 的探活不抛异常、只标记 UNHEALTHY（cloud189 假 Cookie 实测行为）
        CloudAccountResource res = resource("q1", "cloud189", "{\"ssonCookie\":\"x\",\"rootFolderId\":\"-11\"}");
        when(hostExtensionClient.get(CloudAccountResource.class, "q1")).thenReturn(Optional.of(res));
        when(client.settingsClass("cloud189")).thenReturn(
                (Class) com.haki.clouddrive.provider.cloud189.Cloud189Settings.class);
        CloudAccount unhealthy = new CloudAccount("a1", "q1", "cloud189", "{}",
                com.haki.clouddrive.core.domain.HealthStatus.UNHEALTHY, "credential invalid", null,
                Instant.now(), Instant.now());
        when(client.listAccounts()).thenReturn(List.of(unhealthy));
        when(client.replaceSettings(eq("a1"), any())).thenReturn(unhealthy);
        when(client.getAccount("a1")).thenReturn(Optional.of(unhealthy));
        when(client.verifyAccount("a1")).thenReturn(unhealthy);

        assertThatThrownBy(() -> service.verify("q1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("credential invalid");
        verify(hostExtensionClient).updateStatus(CloudAccountResource.class, "q1",
                CloudAccountService.STATUS_UNHEALTHY);
    }

    @Test
    void deleteRemovesMountThenAccountThenTable() {
        CloudAccountResource res = resource("q1", "cloud189", null);
        when(hostExtensionClient.get(CloudAccountResource.class, "q1")).thenReturn(Optional.of(res));
        Mount mount = new Mount("m1", "a1", "/cloud189/q1", "/", true, null, Instant.now());
        when(client.listMounts()).thenReturn(List.of(mount));
        when(client.listAccounts()).thenReturn(List.of(sdkAccount("a1", "q1")));

        service.delete("q1");

        verify(client).deleteMount("m1");
        verify(client).removeAccount("a1");
        verify(hostExtensionClient).delete(CloudAccountResource.class, "q1");
    }

    @Test
    void quotaDelegatesToSdkAccount() throws Exception {
        CloudAccountResource res = resource("q1", "cloud189", "{\"ssonCookie\":\"x\",\"rootFolderId\":\"-11\"}");
        when(hostExtensionClient.get(CloudAccountResource.class, "q1")).thenReturn(Optional.of(res));
        when(client.settingsClass("cloud189")).thenReturn(
                (Class) com.haki.clouddrive.provider.cloud189.Cloud189Settings.class);
        when(client.listAccounts()).thenReturn(List.of(sdkAccount("a1", "q1")));
        when(client.replaceSettings(eq("a1"), any())).thenReturn(sdkAccount("a1", "q1"));
        when(client.getAccount("a1")).thenReturn(Optional.of(sdkAccount("a1", "q1")));
        when(client.quota("a1")).thenReturn(new QuotaInfo(100, 10));

        assertThat(service.quota("q1")).isEqualTo(new QuotaInfo(100, 10));
        verify(hostExtensionClient, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void unsupportedProviderTypeRejected() {
        when(hostExtensionClient.get(CloudAccountResource.class, "x")).thenReturn(Optional.empty());
        when(client.settingsClass("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.create("x", "nope", "{}", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的 Provider");
    }

    @Test
    void malformedSettingsJsonRejected() {
        when(hostExtensionClient.get(CloudAccountResource.class, "x")).thenReturn(Optional.empty());
        when(client.settingsClass("cloud189")).thenReturn(
                (Class) com.haki.clouddrive.provider.cloud189.Cloud189Settings.class);
        assertThatThrownBy(() -> service.create("x", "cloud189", "not-json", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("凭证格式错误");
    }

    @Test
    void mountPathDerivesFromProviderTypeAndName() {
        assertThat(service.mountPath(resource("main", "quark", null))).isEqualTo("/quark/main");
    }
}
