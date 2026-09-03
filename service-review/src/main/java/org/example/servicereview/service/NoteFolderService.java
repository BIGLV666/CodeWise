package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.NoteFolderSaveDto;
import org.example.servicereview.entry.Note;
import org.example.servicereview.entry.NoteFolder;
import org.example.servicereview.mapper.NoteFolderMapper;
import org.example.servicereview.mapper.NoteMapper;
import org.example.servicereview.vo.FolderNoteCountVo;
import org.example.servicereview.vo.NoteFolderVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 笔记文件夹（分类）业务逻辑。
 *
 * <p>身份安全：userId 一律取自网关注入的 {@link UserContext}，不接受客户端提交；
 * 所有读写按归属校验。文件夹删除为级联删除其下笔记（同一事务）。</p>
 */
@Service
public class NoteFolderService {

    private final NoteFolderMapper noteFolderMapper;
    private final NoteMapper noteMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public NoteFolderService(NoteFolderMapper noteFolderMapper, NoteMapper noteMapper) {
        this.noteFolderMapper = noteFolderMapper;
        this.noteMapper = noteMapper;
    }

    /**
     * 我的文件夹列表（含每个文件夹的笔记数，未分类不计入列表）。
     */
    public List<NoteFolderVo> listFolders() {
        Long userId = requireLogin();
        List<NoteFolder> folders = noteFolderMapper.selectList(
                new QueryWrapper<NoteFolder>().eq("user_id", userId).orderByAsc("folder_id"));
        Map<Long, Long> countMap = noteMapper.countByFolder(userId).stream()
                .filter(c -> c.getFolderId() != null)
                .collect(Collectors.toMap(FolderNoteCountVo::getFolderId, FolderNoteCountVo::getNoteCount));
        return folders.stream()
                .map(f -> toVo(f, countMap.getOrDefault(f.getFolderId(), 0L)))
                .toList();
    }

    /**
     * 创建文件夹（可选 requestId 幂等）。
     */
    public NoteFolderVo createFolder(NoteFolderSaveDto dto) {
        Long userId = requireLogin();
        String name = normalizeFolderName(dto == null ? null : dto.getFolderName());
        consumeRequestId(dto == null ? null : dto.getRequestId(), "文件夹");
        NoteFolder folder = NoteFolder.builder()
                .userId(userId)
                .folderName(name)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        noteFolderMapper.insert(folder);
        return toVo(folder, 0L);
    }

    /**
     * 重命名文件夹。
     */
    public NoteFolderVo renameFolder(Long folderId, NoteFolderSaveDto dto) {
        NoteFolder folder = requireOwnedFolder(folderId);
        String name = normalizeFolderName(dto == null ? null : dto.getFolderName());
        folder.setFolderName(name);
        folder.setUpdateTime(LocalDateTime.now());
        noteFolderMapper.updateById(folder);
        return toVo(folder, countNotes(folderId));
    }

    /**
     * 删除文件夹：级联删除其下全部笔记（同一事务，保证不残留孤儿笔记）。
     */
    @Transactional
    public void deleteFolder(Long folderId) {
        NoteFolder folder = requireOwnedFolder(folderId);
        Long userId = UserContext.getUserId();
        noteMapper.delete(new QueryWrapper<Note>().eq("user_id", userId).eq("folder_id", folderId));
        noteFolderMapper.deleteById(folder.getFolderId());
    }

    // ==================== 内部方法 ====================

    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后操作");
        }
        return userId;
    }

    /** 校验文件夹存在且属于当前用户。 */
    private NoteFolder requireOwnedFolder(Long folderId) {
        Long userId = requireLogin();
        NoteFolder folder = noteFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在");
        }
        if (!userId.equals(folder.getUserId())) {
            throw new IllegalArgumentException("无权操作该文件夹");
        }
        return folder;
    }

    private Long countNotes(Long folderId) {
        return noteMapper.selectCount(new QueryWrapper<Note>()
                .eq("user_id", UserContext.getUserId())
                .eq("folder_id", folderId));
    }

    private String normalizeFolderName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("文件夹名称长度不能超过 255 字符");
        }
        return trimmed;
    }

    /** 可选幂等：requestId 为空跳过；否则用 Redis setIfAbsent 防重复提交（3 分钟窗口）。 */
    private void consumeRequestId(String requestId, String subject) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        if (Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(
                RedisContext.REQUEST_ID_KEY + requestId, requestId, 3, TimeUnit.MINUTES))) {
            throw new IllegalArgumentException("该" + subject + "已创建，请勿重复提交");
        }
    }

    private NoteFolderVo toVo(NoteFolder folder, Long noteCount) {
        return NoteFolderVo.builder()
                .folderId(folder.getFolderId())
                .folderName(folder.getFolderName())
                .noteCount(noteCount == null ? 0 : noteCount.intValue())
                .createTime(folder.getCreateTime())
                .updateTime(folder.getUpdateTime())
                .build();
    }
}
