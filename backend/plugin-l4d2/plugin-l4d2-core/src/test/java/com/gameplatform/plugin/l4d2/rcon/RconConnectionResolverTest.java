package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RconConnectionResolverTest {

    private final RconConnectionResolver resolver = new RconConnectionResolver();

    @Test
    void resolve_native_instance_with_configInfo() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015, "rconPassword", "secret"));
        HostVO host = new HostVO();
        host.setIp("192.168.1.100");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals("192.168.1.100", result.get().host());
        assertEquals(27015, result.get().port());
        assertEquals("secret", result.get().password());
    }

    @Test
    void resolve_fallback_to_portConfig_rcon() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPassword", "pwd"));
        instance.setPortConfig(Map.of("rcon", 27017));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27017, result.get().port());
    }

    @Test
    void resolve_fallback_to_default_port_27015() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPassword", "pwd"));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27015, result.get().port());
    }

    @Test
    void resolve_password_fallback_to_compose_variable() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker-compose");
        instance.setConfigInfo(Map.of("rconPort", 27015, "L4D2_RCON_PASSWORD", "compose-pwd"));
        instance.setPortConfig(Map.of("rcon", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals("compose-pwd", result.get().password());
    }

    @Test
    void resolve_password_fallback_to_srcds_variable() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPort", 27015, "SRCDS_RCONPW", "docker-pwd"));
        instance.setPortConfig(Map.of("rcon", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals("docker-pwd", result.get().password());
    }

    @Test
    void resolve_returns_empty_when_password_missing() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_returns_empty_when_host_null() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015, "rconPassword", "pwd"));

        Optional<RconEndpoint> result = resolver.resolve(instance, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_docker_port_not_mapped_returns_empty() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPort", 27017, "rconPassword", "pwd"));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_docker_port_mapped_succeeds() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPort", 27017, "rconPassword", "pwd"));
        instance.setPortConfig(Map.of("rcon", 27017, "game", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27017, result.get().port());
    }

    @Test
    void resolve_linuxgsm_always_mapped() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("linuxgsm");
        instance.setConfigInfo(Map.of("rconPort", 27015, "rconPassword", "pwd"));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
    }
}
