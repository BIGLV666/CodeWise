package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.NoteFolderSaveDto;
import org.example.servicereview.entry.NoteFolder;
import org.example.servicereview.mapper.NoteFolderMapper;
import org.example.servicereview.mapper.NoteMapper;
import org.example.servicereview.vo.FolderNoteCountVo;
import org.example.servicereview.vo.NoteFolderVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NoteFolderService 单元测试：列表计数、创建幂等、重命名归属校验、级联删除。
 */
class NoteFolderServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long FOLDER_A = 101L;

    private NoteFolderMapper noteFolderMapper;
    private NoteMapper noteMapper;
    private NoteFolderService service;

    @BeforeEach
    void setUp() {
        noteFolderMapper = mock(NoteFolderMapper.class);
        noteMapper = mock(NoteMapper.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service = new NoteFolderService(noteFolderMapper, noteMapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private NoteFolder folder(Long id, Long ownerId, String name) {
        return NoteFolder.builder()
                .folderId(id)
                .userId(ownerId)
                .folderName(name)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    @Test
    void listFolders_returnsVosWithCount() {
        when(noteFolderMapper.selectList(any())).thenReturn(List.of(
                folder(101L, USER_ID, "Java"),
                folder(102L, USER_ID, "算法")
        ));
        when(noteMapper.countByFolder(USER_ID)).thenReturn(List.of(
                FolderNoteCountVo.builder().folderId(101L).noteCount(3L).build(),
                FolderNoteCountVo.builder().folderId(102L).noteCount(0L).build(),
                FolderNoteCountVo.builder().folderId(null).noteCount(5L).build()
        ));

        List<NoteFolderVo> vos = service.listFolders();

        assertEquals(2, vos.size());
        assertEquals(3, vos.get(0).getNoteCount());
        assertEquals(0, vos.get(1).getNoteCount());
    }

    @Test
    void listFolders_unauthenticated_throws() {
        UserContext.clear();
        assertThrows(IllegalArgumentException.class, () -> service.listFolders());
    }

    @Test
    void createFolder_success_backfillsId() {
        ValueOperations<String, Object> valueOps = valueOps();
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        doAnswer(invocation -> {
            NoteFolder saved = invocation.getArgument(0);
            saved.setFolderId(99L);
            return 1;
        }).when(noteFolderMapper).insert(any(NoteFolder.class));

        NoteFolderSaveDto dto = new NoteFolderSaveDto();
        dto.setFolderName("  Java  ");
        dto.setRequestId("uuid-1");

        NoteFolderVo vo = service.createFolder(dto);

        assertEquals(99L, vo.getFolderId());
        assertEquals("Java", vo.getFolderName());
        assertEquals(0, vo.getNoteCount());
    }

    @Test
    void createFolder_blankName_throws() {
        NoteFolderSaveDto dto = new NoteFolderSaveDto();
        dto.setFolderName("   ");
        assertThrows(IllegalArgumentException.class, () -> service.createFolder(dto));
        verify(noteFolderMapper, never()).insert(any(NoteFolder.class));
    }

    @Test
    void createFolder_duplicateRequestId_throws() {
        ValueOperations<String, Object> valueOps = valueOps();
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        NoteFolderSaveDto dto = new NoteFolderSaveDto();
        dto.setFolderName("Java");
        dto.setRequestId("uuid-1");

        assertThrows(IllegalArgumentException.class, () -> service.createFolder(dto));
        verify(noteFolderMapper, never()).insert(any(NoteFolder.class));
    }

    @Test
    void renameFolder_notOwner_throws() {
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, OTHER_USER_ID, "Java"));

        NoteFolderSaveDto dto = new NoteFolderSaveDto();
        dto.setFolderName("新名");
        assertThrows(IllegalArgumentException.class, () -> service.renameFolder(FOLDER_A, dto));
        verify(noteFolderMapper, never()).updateById(any(NoteFolder.class));
    }

    @Test
    void deleteFolder_cascadesNotes() {
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, "Java"));
        when(noteMapper.delete(any(QueryWrapper.class))).thenReturn(1);

        service.deleteFolder(FOLDER_A);

        verify(noteMapper).delete(any(QueryWrapper.class));
        verify(noteFolderMapper).deleteById(FOLDER_A);
    }

    @Test
    void deleteFolder_notOwner_throws() {
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, OTHER_USER_ID, "Java"));

        assertThrows(IllegalArgumentException.class, () -> service.deleteFolder(FOLDER_A));
        verify(noteMapper, never()).delete(any(QueryWrapper.class));
        verify(noteFolderMapper, never()).deleteById(eq(FOLDER_A));
    }

    private ValueOperations<String, Object> valueOps() {
        RedisTemplate<String, Object> redisTemplate =
                (RedisTemplate<String, Object>) ReflectionTestUtils.getField(service, "redisTemplate");
        return redisTemplate.opsForValue();
    }
}
