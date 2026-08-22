package org.example.servicejudge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class DockerConfig {

    /** Docker Engine 地址；默认使用本机 Unix socket，不允许回退到明文 TCP。 */
    @Value("${docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    /** 是否启用 Docker TLS；远程 TCP 连接必须显式开启。 */
    @Value("${docker.tls-verify:false}")
    private boolean tlsVerify;

    /** Docker TLS 客户端证书目录，使用远程 TLS 时必须配置。 */
    @Value("${docker.cert-path:}")
    private String dockerCertPath;

    /**
     * 创建 Docker 客户端。
     *
     * <p>Docker TCP 2375 不提供传输层加密和身份认证，攻击者一旦能够访问该端口
     * 就可以取得宿主机 Docker 控制权。因此明文 {@code tcp://} 地址直接拒绝启动；
     * 远程连接必须使用 TLS，并提供客户端证书目录。</p>
     *
     * @return 已完成安全配置的 Docker 客户端
     * @throws IllegalStateException Docker 地址或 TLS 配置不安全时抛出
     */
    @Bean
    public DockerClient dockerClient() {
        String normalizedHost = dockerHost == null ? "" : dockerHost.trim().toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("tcp://") && !tlsVerify) {
            throw new IllegalStateException("禁止使用未加密的 Docker TCP 2375，请配置 TLS 或本机 socket");
        }
        if (normalizedHost.startsWith("tcp://") && (dockerCertPath == null || dockerCertPath.isBlank())) {
            throw new IllegalStateException("Docker TLS 连接必须配置 docker.cert-path");
        }

        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withDockerTlsVerify(tlsVerify);
        if (dockerCertPath != null && !dockerCertPath.isBlank()) {
            configBuilder.withDockerCertPath(dockerCertPath);
        }
        DockerClientConfig config = configBuilder.build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}