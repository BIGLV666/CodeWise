package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.NoteCreateDto;
import org.example.servicereview.dto.NoteUpdateDto;
import org.example.servicereview.entry.Note;
import org.example.servicereview.entry.NoteFolder;
import org.example.servicereview.handler.NoteContentHandler;
import org.example.servicereview.handler.NoteContentHandlerRegistry;
import org.example.servicereview.mapper.NoteFolderMapper;
import org.example.servicereview.mapper.NoteMapper;
import org.example.servicereview.vo.NoteBriefVo;
import org.example.servicereview.vo.NoteDetailVo;
import org.example.servicereview.vo.NoteListVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 笔记业务逻辑。
 *
 * <p>身份安全：userId 一律取自网关注入的 {@link UserContext}；所有读写按归属校验。
 * 内容类型的校验/预览/标题推导经 {@link NoteContentHandlerRegistry} 分发，
 * 新增类型只需新增 handler，不改本类。</p>
 */
@Service
public class NoteService {

    /** 列表页默认与上限。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 列表预览截断长度。 */
    private static final int PREVIEW_LENGTH = 120;
    /** 无标题时兜底标题。 */
    private static final String UNTITLED = "未命名笔记";

    private final NoteMapper noteMapper;
    private final NoteFolderMapper noteFolderMapper;
    private final NoteContentHandlerRegistry registry;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public NoteService(NoteMapper noteMapper, NoteFolderMapper noteFolderMapper,
                       NoteContentHandlerRegistry registry) {
        this.noteMapper = noteMapper;
        this.noteFolderMapper = noteFolderMapper;
        this.registry = registry;
    }

    /**
     * 笔记列表（游标分页，返回预览不含全文）。
     *
     * <p>folderId 语义：缺省/空串 → 全部笔记；正数 → 该文件夹；0 → 未分类
     * （folder_id IS NULL）。负数视为非法参数。</p>
     */
    public NoteListVo listNotes(Long folderId, Long cursor, Integer size) {
        Long userId = requireLogin();
        boolean uncategorized = false;
        if (folderId != null) {
            if (folderId < 0) {
                throw new IllegalArgumentException("文件夹 ID 不合法");
            }
            if (folderId == 0L) {
                uncategorized = true;
                folderId = null; // 未分类：folder_id IS NULL
            } else {
                requireOwnedFolder(folderId);
            }
        }
        int pageSize = normalizeSize(size);
        List<Note> notes = noteMapper.selectPageByCursor(userId, folderId, uncategorized, cursor, pageSize + 1);
        boolean hasNext = notes.size() > pageSize;
        List<Note> page = hasNext ? notes.subList(0, pageSize) : notes;
        Long nextCursor = hasNext && !page.isEmpty() ? page.get(page.size() - 1).getNoteId() : null;
        List<NoteBriefVo> items = page.stream().map(this::toBriefVo).toList();
        Long total = noteMapper.selectCount(buildFolderQuery(userId, folderId, uncategorized));
        return NoteListVo.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .total(total == null ? 0L : total)
                .build();
    }

    /**
     * 笔记详情（全文，校验归属）。
     */
    public NoteDetailVo getDetail(Long noteId) {
        return toDetailVo(requireOwnedNote(noteId));
    }

    /**
     * 创建笔记：校验正文与文件夹归属，标题为空时从正文推导，内容类型默认为 MD。
     */
    public NoteDetailVo createNote(NoteCreateDto dto) {
        Long userId = requireLogin();
        if (dto == null || dto.getContent() == null || dto.getContent().isBlank()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        NoteContentHandler handler = registry.resolve(dto.getType());
        handler.validate(dto.getContent());
        Long resolvedFolderId = normalizeFolderId(dto.getFolderId());
        consumeRequestId(dto.getRequestId(), "笔记");
        String content = dto.getContent();
        String title = normalizeTitle(dto.getTitle(), handler, content);
        Note note = Note.builder()
                .userId(userId)
                .folderId(resolvedFolderId)
                .title(title)
                .content(content)
                .contentType(handler.type())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        noteMapper.insert(note);
        return toDetailVo(note);
    }

    /**
     * 更新笔记标题/正文（内容类型创建后不可变）。
     */
    public NoteDetailVo updateNote(Long noteId, NoteUpdateDto dto) {
        Note note = requireOwnedNote(noteId);
        if (dto == null) {
            throw new IllegalArgumentException("未提供任何需要更新的字段");
        }
        NoteContentHandler handler = registry.resolve(note.getContentType());
        boolean updated = false;
        if (dto.getTitle() != null) {
            note.setTitle(normalizeTitle(dto.getTitle(), handler, note.getContent()));
            updated = true;
        }
        if (dto.getContent() != null) {
            handler.validate(dto.getContent());
            note.setContent(dto.getContent());
            updated = true;
        }
        if (!updated) {
            throw new IllegalArgumentException("未提供任何需要更新的字段");
        }
        note.setUpdateTime(LocalDateTime.now());
        noteMapper.updateById(note);
        return toDetailVo(note);
    }

    /**
     * 移动笔记到指定文件夹；folderId 缺省/空串/0 均表示移到未分类，正数表示目标文件夹。
     */
    public void moveNote(Long noteId, Long folderId) {
        Note note = requireOwnedNote(noteId);
        Long resolvedFolderId = normalizeFolderId(folderId);
        // 移到未分类时置 null；Note.folderId 已标 @TableField(updateStrategy=ALWAYS)，
        // 因此 updateById 会真正写入 null（否则 MyBatis-Plus 默认忽略 null 字段，清不掉 folder_id）。
        note.setFolderId(resolvedFolderId);
        note.setUpdateTime(LocalDateTime.now());
        noteMapper.updateById(note);
    }

    /**
     * 删除笔记。
     */
    public void deleteNote(Long noteId) {
        Note note = requireOwnedNote(noteId);
        noteMapper.deleteById(note.getNoteId());
    }

    // ==================== 内部方法 ====================

    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后操作");
        }
        return userId;
    }

    /** 校验笔记存在且属于当前用户。 */
    private Note requireOwnedNote(Long noteId) {
        Long userId = requireLogin();
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在");
        }
        if (!userId.equals(note.getUserId())) {
            throw new IllegalArgumentException("无权操作该笔记");
        }
        return note;
    }

    /** 校验文件夹存在且属于当前用户（笔记挂靠/移动时的归属前置校验）。 */
    private void requireOwnedFolder(Long folderId) {
        NoteFolder folder = noteFolderMapper.selectById(folderId);
        if (folder == null || !UserContext.getUserId().equals(folder.getUserId())) {
            throw new IllegalArgumentException("文件夹不存在或无权访问");
        }
    }

    /**
     * 归一化「单个笔记归属」的文件夹参数：null/0 → 未分类（返回 null）；正数 → 校验归属后返回；
     * 负数 → 报错。不含「全部」语义（列表查询另行处理）。
     */
    private Long normalizeFolderId(Long folderId) {
        if (folderId == null || folderId == 0L) {
            return null;
        }
        if (folderId < 0) {
            throw new IllegalArgumentException("文件夹 ID 不合法");
        }
        requireOwnedFolder(folderId);
        return folderId;
    }

    /** 列表文件夹过滤条件：uncategorized 用 IS NULL，folderId 非空用等值，否则不过滤。 */
    private QueryWrapper<Note> buildFolderQuery(Long userId, Long folderId, boolean uncategorized) {
        QueryWrapper<Note> query = new QueryWrapper<Note>().eq("user_id", userId);
        if (uncategorized) {
            query.isNull("folder_id");
        } else if (folderId != null) {
            query.eq("folder_id", folderId);
        }
        return query;
    }

    private String normalizeTitle(String title, NoteContentHandler handler, String content) {
        if (title != null && !title.trim().isEmpty()) {
            String trimmed = title.trim();
            if (trimmed.length() > 255) {
                throw new IllegalArgumentException("笔记标题长度不能超过 255 字符");
            }
            return trimmed;
        }
        String suggested = handler.suggestTitle(content);
        if (suggested != null && !suggested.isBlank()) {
            return suggested;
        }
        return UNTITLED;
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size <= 0) {
            throw new IllegalArgumentException("分页大小必须为正整数");
        }
        return Math.min(size, MAX_PAGE_SIZE);
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

    private NoteBriefVo toBriefVo(Note note) {
        NoteContentHandler handler = registry.resolve(note.getContentType());
        return NoteBriefVo.builder()
                .noteId(note.getNoteId())
                .title(note.getTitle())
                .folderId(note.getFolderId())
                .contentType(note.getContentType())
                .preview(handler.toPreview(note.getContent(), PREVIEW_LENGTH))
                .createTime(note.getCreateTime())
                .updateTime(note.getUpdateTime())
                .build();
    }

    private NoteDetailVo toDetailVo(Note note) {
        return NoteDetailVo.builder()
                .noteId(note.getNoteId())
                .title(note.getTitle())
                .folderId(note.getFolderId())
                .contentType(note.getContentType())
                .content(note.getContent())
                .createTime(note.getCreateTime())
                .updateTime(note.getUpdateTime())
                .build();
    }
}
