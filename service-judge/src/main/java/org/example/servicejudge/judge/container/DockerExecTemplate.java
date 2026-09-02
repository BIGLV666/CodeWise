package org.example.servicejudge.judge.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.example.servicejudge.judge.LanguageSpec;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Docker 执行模板：封装容器创建/删除、工作区清理与复制、脚本执行、
 * 文件读取与内存统计等纯 Docker I/O 操作（原 {@code JudgeService} 迁出）。
 *
 * <p>本类不持有任何容器池状态；执行超时或异常时的「容器污染」标记通过
 * {@link Consumer} 回调参数由调用侧（容器池管理器/判题门面）注入，
 * 避免与 {@link ContainerPoolManager} 形成循环依赖。</p>
 */
@Component
@Slf4j
public class DockerExecTemplate {

    // ========== 容器资源限制常量（原 JudgeService 迁出） ==========
    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024;
    private static final long CPU_PERIOD_MICROS = 100_000L;
    private static final long CPU_QUOTA_MICROS = 100_000L;
    private static final long PID_LIMIT = 64L;
    private static final String JUDGE_USER = "1000:1000";

    private final DockerClient dockerClient;

    public DockerExecTemplate(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * 创建并启动一个受安全约束的判题容器。
     *
     * <p>资源限制和权限限制必须在容器创建时设置，不能依赖用户提交的
     * 脚本自觉遵守：内存/CPU/进程数上限、禁网络、丢弃全部 Linux capabilities、
     * 禁提权，/tmp 落在受限 tmpfs 上，判题进程以 uid 1000 非 root 运行
     * （系统目录 root 属主，不可写）。</p>
     *
     * <p>不启用 HostConfig.readonlyRootfs、/workspace 不用 tmpfs：新版 Moby（29+，
     * containerd 存储）下 docker cp 对只读容器直接拒绝（400 rootfs read-only），
     * 对 tmpfs 挂载点则静默写进被遮挡的 rootfs 路径（exec 不可见）。
     * /workspace 由 judge-base 镜像预建为 judge 用户属主目录，判题文件经
     * docker cp 写入容器层，exec 可见；非 root 用户对系统路径无写权限，
     * 隔离由「非 root + capabilities 全清 + no-new-privileges + tmpfs /tmp」保证。</p>
     *
     * @param spec 语言规格（提供镜像名）
     * @return 已启动容器的 ID
     */
    public String createContainer(LanguageSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(MEMORY_LIMIT_BYTES)
                .withMemorySwap(MEMORY_LIMIT_BYTES)
                .withCpuPeriod(CPU_PERIOD_MICROS)
                .withCpuQuota(CPU_QUOTA_MICROS)
                .withPidsLimit(PID_LIMIT)
                .withNetworkMode("none")
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(java.util.List.of("no-new-privileges:true"))
                .withTmpFs(Map.of(
                        "/tmp", "rw,uid=1000,gid=1000,size=16m"
                ));

        CreateContainerResponse container = dockerClient.createContainerCmd(spec.image())
                .withHostConfig(hostConfig)
                .withUser(JUDGE_USER)
                .withWorkingDir("/workspace")
                .withCmd("sh", "-c", "while true; do sleep 3600; done")
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();

        return container.getId();
    }

    /**
     * 删除容器（强制），失败只记录日志不抛异常。
     *
     * @param containerId 容器 ID
     */
    public void removeContainerQuietly(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.info("删除容器成功: {}", containerId);
        } catch (Exception exception) {
            log.warn("删除容器失败: {}", containerId, exception);
        }
    }

    /**
     * 清空容器 /workspace 目录。
     *
     * @param containerId 容器 ID
     * @return 清理成功（2 秒内完成且退出码为 0）返回 true；超时或失败返回 false
     */
    public boolean cleanContainerWorkspace(String containerId) {
        try {
            // 删除 /workspace 下的所有文件
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "find /workspace -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>())
                    .awaitCompletion(2, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("清理容器工作目录超时: {}", containerId);
                return false;
            }
            Integer exitCode = dockerClient.inspectExecCmd(execCmd.getId()).exec().getExitCode();
            if (exitCode == null || exitCode != 0) {
                log.warn("清理容器工作目录失败: containerId={}, exitCode={}", containerId, exitCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("清理容器工作目录失败", e);
            return false;
        }
    }

    /**
     * 复制本地工作目录到容器 /workspace（内存中打包为 tar 后传输）。
     *
     * @param containerId 容器 ID
     * @param workspace   本地临时工作目录
     * @throws IOException 打包或传输失败
     */
    public void copyWorkspaceToContainer(String containerId, Path workspace) throws IOException {
        // 用内存流打包
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(baos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Files.walk(workspace)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = workspace.relativize(file).toString();
                            TarArchiveEntry entry = new TarArchiveEntry(entryName);
                            entry.setSize(Files.size(file));
                            tos.putArchiveEntry(entry);
                            Files.copy(file, tos);
                            tos.closeArchiveEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            tos.finish();
        }

        // 从内存复制到容器
        try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(bais)
                    .withRemotePath("/workspace/")
                    .exec();
        }
    }

    /**
     * 向容器写入单个 input.txt（打包临时目录后复制）。
     *
     * @param containerId 容器 ID
     * @param input       输入内容，null 视为空串
     * @throws IOException 打包或传输失败
     */
    public void writeInputToContainer(String containerId, String input) throws IOException {
        Path temp = Files.createTempDirectory("codewise-judge-input");
        try {
            Files.writeString(temp.resolve("input.txt"), input == null ? "" : input, StandardCharsets.UTF_8);
            copyWorkspaceToContainer(containerId, temp);
        } finally {
            deleteDirectory(temp);
        }
    }

    /**
     * 以默认时限（单用例时限 + 1 秒开销）在容器内执行脚本。
     *
     * @param containerId 容器 ID
     * @param scriptPath  容器内脚本绝对路径
     * @param taintMarker 容器污染回调（超时/异常时接收容器 ID），由调用侧注入
     * @return 退出码；超时返回 124；中断或异常返回 1
     */
    public Integer executeScriptInContainer(String containerId, String scriptPath, Consumer<String> taintMarker) {
        return executeScriptInContainerWithTimeout(
                containerId,
                scriptPath,
                LanguageSpec.TIME_LIMIT_MS + 1000L,
                taintMarker);
    }

    /**
     * 以显式时限在容器内执行脚本。
     *
     * <p>Docker exec 超时只会结束等待，不保证被执行程序的子进程已退出，
     * 因此超时与执行异常都会通过 {@code taintMarker} 上报容器 ID，
     * 由容器池在归还时销毁重建以清理整棵进程树。</p>
     *
     * @param containerId   容器 ID
     * @param scriptPath    容器内脚本绝对路径
     * @param timeoutMillis 等待上限（毫秒）
     * @param taintMarker   容器污染回调（超时/异常时接收容器 ID），由调用侧注入
     * @return 退出码；超时返回 124；中断或异常返回 1
     */
    public Integer executeScriptInContainerWithTimeout(
            String containerId,
            String scriptPath,
            long timeoutMillis,
            Consumer<String> taintMarker
    ) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", scriptPath)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>())
                    .awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                markTainted(taintMarker, containerId);
                // 超时大概率是 TLE，按容器内 timeout 约定返回 124
                log.warn("容器内执行超时，标记容器并重建以清理进程树: containerId={}, scriptPath={}",
                        containerId, scriptPath);
                return 124;
            }
            Integer exitCode = dockerClient.inspectExecCmd(execCmd.getId()).exec().getExitCode();
            return exitCode != null ? exitCode : 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception e) {
            markTainted(taintMarker, containerId);
            log.error("批量执行命令失败", e);
            return 1;
        }
    }

    /**
     * 执行函数模式批量脚本：一个 JVM 顺序跑完所有用例，时限随用例数线性扩展。
     *
     * @param containerId   容器 ID
     * @param testCaseCount 用例数
     * @param taintMarker   容器污染回调（超时/异常时接收容器 ID），由调用侧注入
     * @return 退出码；超时返回 124；中断或异常返回 1
     */
    public Integer executeFunctionBatchScript(String containerId, int testCaseCount, Consumer<String> taintMarker) {
        long timeoutMillis = Math.max(
                LanguageSpec.TIME_LIMIT_MS + LanguageSpec.EXEC_OVERHEAD_MS,
                (LanguageSpec.TIME_LIMIT_MS + LanguageSpec.EXEC_OVERHEAD_MS) * testCaseCount
                        + LanguageSpec.BATCH_STARTUP_TIMEOUT_MS
        );
        return executeScriptInContainerWithTimeout(containerId, "/workspace/run-batch.sh", timeoutMillis, taintMarker);
    }

    /**
     * 读取容器内文本文件。
     *
     * @param containerId 容器 ID
     * @param path        容器内文件绝对路径
     * @return 文件内容（UTF-8）；读取失败返回空字符串
     */
    public String readFileFromContainer(String containerId, String path) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("cat", path)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame item) {
                            try {
                                out.write(item.getPayload());
                            } catch (IOException e) {
                                log.warn("读取输出失败", e);
                            }
                        }
                    })
                    .awaitCompletion(2, TimeUnit.SECONDS);

            return out.toString(StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.warn("读取容器文件失败: {}", path, e);
            return "";
        }
    }

    /**
     * 获取容器内存使用量（KB）。
     *
     * @param containerId 容器 ID
     * @return 内存峰值（KB）；获取失败返回 0
     */
    public int getContainerMemory(String containerId) {
        try {
            final int[] memoryKB = {0};

            dockerClient.statsCmd(containerId)
                    .exec(new ResultCallback.Adapter<com.github.dockerjava.api.model.Statistics>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Statistics stats) {
                            if (stats != null && stats.getMemoryStats() != null) {
                                long usage = stats.getMemoryStats().getUsage();
                                memoryKB[0] = (int) (usage / 1024);
                            }
                        }
                    });

            return memoryKB[0];
        } catch (Exception e) {
            log.warn("获取内存使用失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 递归删除本地临时目录（判题工作区清理），失败静默忽略。
     *
     * @param directory 目录路径，可为 null
     */
    public static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walk(directory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /** 污染回调存在时上报容器 ID；回调自身异常不阻断执行流程。 */
    private static void markTainted(Consumer<String> taintMarker, String containerId) {
        if (taintMarker == null) {
            return;
        }
        try {
            taintMarker.accept(containerId);
        } catch (Exception exception) {
            log.warn("标记容器污染失败: containerId={}", containerId, exception);
        }
    }
}
