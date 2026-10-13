package org.example.servicejudge.judge.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 工作区打包的属主与目录条目回归测试。
 *
 * <p>docker cp 由守护进程以 root 解包，条目不带属主时落地为 root:root；判题用户（uid 1000）
 * 既删不掉 root 属主子目录里的文件、又改不了属主，会让每次归还容器时的清理失败并触发容器重建。
 * 因此这里直接解析传给 daemon 的 tar 流，锁死「属主 1000:1000 + 目录条目显式存在」这条契约。</p>
 */
class DockerExecTemplateTarOwnershipTest {

    private static final int JUDGE_UID = 1000;
    private static final int JUDGE_GID = 1000;

    @TempDir
    Path workspace;

    @Test
    void shouldPackDirectoriesExplicitlyWithJudgeOwnership() throws Exception {
        Files.writeString(workspace.resolve("Main.java"), "class Main {}\n");
        Files.createDirectories(workspace.resolve("cases"));
        Files.writeString(workspace.resolve("cases/case-000001.txt"), "1\n");
        Files.writeString(workspace.resolve("cases/case-000002.txt"), "2\n");

        List<TarArchiveEntry> entries = packWorkspace();
        Map<String, TarArchiveEntry> byName = new LinkedHashMap<>();
        entries.forEach(entry -> byName.put(entry.getName(), entry));

        assertAll(
                () -> assertTrue(byName.containsKey("Main.java"), "缺少源文件条目"),
                () -> assertTrue(byName.containsKey("cases/"), "缺少目录条目：目录靠守护进程隐式创建时会成为 root 属主"),
                () -> assertTrue(byName.containsKey("cases/case-000001.txt"), "缺少用例文件条目"),
                () -> assertTrue(byName.containsKey("cases/case-000002.txt"), "缺少用例文件条目"),
                () -> assertFalse(byName.containsKey("") , "工作区根目录不应作为条目打包"),
                () -> assertFalse(byName.containsKey("./"), "工作区根目录不应作为条目打包"),
                () -> assertTrue(byName.get("cases/").isDirectory(), "cases/ 必须是目录条目"),
                () -> assertTrue(
                        entries.stream().allMatch(entry -> entry.getUserId() == JUDGE_UID),
                        "所有条目 uid 必须为 1000"),
                () -> assertTrue(
                        entries.stream().allMatch(entry -> entry.getGroupId() == JUDGE_GID),
                        "所有条目 gid 必须为 1000"),
                () -> assertTrue(
                        entries.indexOf(byName.get("cases/")) < entries.indexOf(byName.get("cases/case-000001.txt")),
                        "目录条目应先于其内容出现（解包顺序）")
        );
    }

    @Test
    void shouldKeepFileContentsWhenPackingWorkspace() throws Exception {
        Files.writeString(workspace.resolve("Main.java"), "class Main {}\n");
        Files.writeString(workspace.resolve("run.sh"), "#!/bin/sh\necho 0\n");
        Files.createDirectories(workspace.resolve("cases"));
        Files.writeString(workspace.resolve("cases/case-000001.txt"), "42\n");

        Map<String, String> contents = packWorkspaceContents();

        assertAll(
                () -> assertEquals("class Main {}\n", contents.get("Main.java")),
                () -> assertEquals("#!/bin/sh\necho 0\n", contents.get("run.sh")),
                () -> assertEquals("42\n", contents.get("cases/case-000001.txt")),
                () -> assertFalse(contents.containsKey("cases/"), "目录条目不应带内容")
        );
    }

    @Test
    void shouldWriteSingleInputFileWithJudgeOwnership() throws Exception {
        DockerClient dockerClient = mockDockerClient();
        ArgumentCaptor<InputStream> tarCaptor = ArgumentCaptor.forClass(InputStream.class);

        new DockerExecTemplate(dockerClient).writeInputToContainer("container-1", "7\n");

        verify(dockerClient.copyArchiveToContainerCmd("container-1"))
                .withTarInputStream(tarCaptor.capture());
        List<TarArchiveEntry> entries = readEntries(tarCaptor.getValue());

        assertAll(
                () -> assertEquals(1, entries.size(), "writeInputToContainer 只应写入 input.txt"),
                () -> assertEquals("input.txt", entries.get(0).getName()),
                () -> assertEquals(JUDGE_UID, entries.get(0).getUserId()),
                () -> assertEquals(JUDGE_GID, entries.get(0).getGroupId()),
                () -> assertFalse(entries.get(0).isDirectory())
        );
    }

    /** 走真实打包逻辑：mock 掉 DockerClient，只捕获传给 daemon 的 tar 流。 */
    private List<TarArchiveEntry> packWorkspace() throws IOException {
        DockerClient dockerClient = mockDockerClient();
        ArgumentCaptor<InputStream> tarCaptor = ArgumentCaptor.forClass(InputStream.class);

        new DockerExecTemplate(dockerClient).copyWorkspaceToContainer("container-1", workspace);

        verify(dockerClient.copyArchiveToContainerCmd("container-1"))
                .withTarInputStream(tarCaptor.capture());
        return readEntries(tarCaptor.getValue());
    }

    private Map<String, String> packWorkspaceContents() throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        DockerClient dockerClient = mockDockerClient();
        ArgumentCaptor<InputStream> tarCaptor = ArgumentCaptor.forClass(InputStream.class);

        new DockerExecTemplate(dockerClient).copyWorkspaceToContainer("container-1", workspace);

        verify(dockerClient.copyArchiveToContainerCmd("container-1"))
                .withTarInputStream(tarCaptor.capture());
        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarCaptor.getValue())) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    contents.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return contents;
    }

    private DockerClient mockDockerClient() {
        DockerClient dockerClient = mock(DockerClient.class);
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class);
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenReturn(copyCmd);
        when(copyCmd.withRemotePath(anyString())).thenReturn(copyCmd);
        return dockerClient;
    }

    private List<TarArchiveEntry> readEntries(InputStream tarStream) throws IOException {
        List<TarArchiveEntry> entries = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarStream)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
