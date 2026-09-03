package org.example.servicereview.service;

import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.NoteCreateDto;
import org.example.servicereview.dto.NoteUpdateDto;
import org.example.servicereview.entry.Note;
import org.example.servicereview.entry.NoteFolder;
import org.example.servicereview.handler.MarkdownNoteContentHandler;
import org.example.servicereview.handler.NoteContentHandlerRegistry;
import org.example.servicereview.mapper.NoteFolderMapper;
import org.example.servicereview.mapper.NoteMapper;
import org.example.servicereview.vo.NoteDetailVo;
import org.example.servicereview.vo.NoteListVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NoteService 单元测试：游标分页、归属校验、创建/更新/移动/删除、类型扩展点。
 */
class NoteServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long FOLDER_A = 101L;
    private static final Long NOTE_ID = 1L;

    private NoteMapper noteMapper;
    private NoteFolderMapper noteFolderMapper;
    private NoteService service;

    @BeforeEach
    void setUp() {
        noteMapper = mock(NoteMapper.class);
        noteFolderMapper = mock(NoteFolderMapper.class);
        NoteContentHandlerRegistry registry =
                new NoteContentHandlerRegistry(List.of(new MarkdownNoteContentHandler()));
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service = new NoteService(noteMapper, noteFolderMapper, registry);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Note note(Long id, Long ownerId, Long folderId, String title, String content) {
        return Note.builder()
                .noteId(id)
                .userId(ownerId)
                .folderId(folderId)
                .title(title)
                .content(content)
                .contentType(MarkdownNoteContentHandler.TYPE)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    @Test
    void listNotes_returnsPageWithNextCursor() {
        List<Note> notes = LongStream.rangeClosed(11, 31)
                .mapToObj(i -> note(31 - (i - 11), USER_ID, FOLDER_A, "标题" + i, "内容" + i))
                .toList(); // noteId 从 31 递减到 11，共 21 条
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(
                NoteFolder.builder().folderId(FOLDER_A).userId(USER_ID).folderName("Java").build());
        when(noteMapper.selectPageByCursor(eq(USER_ID), eq(FOLDER_A), eq(false), nullable(Long.class), anyInt()))
                .thenReturn(notes);
        when(noteMapper.selectCount(any())).thenReturn(30L);

        NoteListVo vo = service.listNotes(FOLDER_A, null, 20);

        assertEquals(20, vo.getItems().size());
        assertEquals(Boolean.TRUE, vo.getHasNext());
        assertEquals(12L, vo.getNextCursor()); // 第 20 条的 note_id（31 起递减到 12）
        assertEquals(30L, vo.getTotal());
    }

    @Test
    void listNotes_uncategorized_passesIsNullFlag() {
        when(noteMapper.selectPageByCursor(eq(USER_ID), isNull(), eq(true), isNull(), eq(21)))
                .thenReturn(List.of());
        when(noteMapper.selectCount(any())).thenReturn(0L);

        NoteListVo vo = service.listNotes(0L, null, 20);

        assertEquals(0, vo.getItems().size());
        assertEquals(Boolean.FALSE, vo.getHasNext());
        assertNull(vo.getNextCursor());
        verify(noteFolderMapper, never()).selectById(anyLong());
    }

    @Test
    void listNotes_negativeFolderId_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.listNotes(-1L, null, 20));
        verify(noteMapper, never()).selectPageByCursor(any(), any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void getDetail_notOwner_throws() {
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note(NOTE_ID, OTHER_USER_ID, null, "t", "c"));
        assertThrows(IllegalArgumentException.class, () -> service.getDetail(NOTE_ID));
    }

    @Test
    void createNote_success_derivesTitleAndDefaultType() {
        doAnswer(invocation -> {
            Note saved = invocation.getArgument(0);
            saved.setNoteId(99L);
            return 1;
        }).when(noteMapper).insert(any(Note.class));

        NoteCreateDto dto = new NoteCreateDto();
        dto.setContent("# Hello\n\n正文");

        NoteDetailVo vo = service.createNote(dto);

        assertEquals(99L, vo.getNoteId());
        assertEquals("Hello", vo.getTitle());
        assertEquals(MarkdownNoteContentHandler.TYPE, vo.getContentType());
    }

    @Test
    void createNote_unknownType_throws() {
        NoteCreateDto dto = new NoteCreateDto();
        dto.setContent("正文");
        dto.setType("IMAGE");
        assertThrows(IllegalArgumentException.class, () -> service.createNote(dto));
        verify(noteMapper, never()).insert(any(Note.class));
    }

    @Test
    void createNote_blankContent_throws() {
        NoteCreateDto dto = new NoteCreateDto();
        dto.setContent("   ");
        assertThrows(IllegalArgumentException.class, () -> service.createNote(dto));
        verify(noteMapper, never()).insert(any(Note.class));
    }

    @Test
    void createNote_folderNotOwned_throws() {
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(
                NoteFolder.builder().folderId(FOLDER_A).userId(OTHER_USER_ID).folderName("Java").build());

        NoteCreateDto dto = new NoteCreateDto();
        dto.setFolderId(FOLDER_A);
        dto.setContent("正文");
        assertThrows(IllegalArgumentException.class, () -> service.createNote(dto));
        verify(noteMapper, never()).insert(any(Note.class));
    }

    @Test
    void createNote_folderIdZero_meansUncategorized() {
        doAnswer(invocation -> {
            Note saved = invocation.getArgument(0);
            saved.setNoteId(99L);
            return 1;
        }).when(noteMapper).insert(any(Note.class));

        NoteCreateDto dto = new NoteCreateDto();
        dto.setFolderId(0L);
        dto.setTitle("标题");
        dto.setContent("正文");

        NoteDetailVo vo = service.createNote(dto);

        assertNull(vo.getFolderId());
        verify(noteFolderMapper, never()).selectById(anyLong());
    }

    @Test
    void updateNote_updatesTitleAndContent() {
        Note existing = note(NOTE_ID, USER_ID, null, "旧标题", "旧内容");
        when(noteMapper.selectById(NOTE_ID)).thenReturn(existing);

        NoteUpdateDto dto = new NoteUpdateDto();
        dto.setTitle("新标题");
        dto.setContent("新内容");

        NoteDetailVo vo = service.updateNote(NOTE_ID, dto);

        assertEquals("新标题", vo.getTitle());
        assertEquals("新内容", vo.getContent());
        verify(noteMapper).updateById(existing);
    }

    @Test
    void updateNote_noFields_throws() {
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note(NOTE_ID, USER_ID, null, "t", "c"));

        assertThrows(IllegalArgumentException.class, () -> service.updateNote(NOTE_ID, new NoteUpdateDto()));
        verify(noteMapper, never()).updateById(any(Note.class));
    }

    @Test
    void moveNote_toFolder_updatesFolderId() {
        Note existing = note(NOTE_ID, USER_ID, null, "t", "c");
        when(noteMapper.selectById(NOTE_ID)).thenReturn(existing);
        when(noteFolderMapper.selectById(FOLDER_A)).thenReturn(
                NoteFolder.builder().folderId(FOLDER_A).userId(USER_ID).folderName("Java").build());

        service.moveNote(NOTE_ID, FOLDER_A);

        assertEquals(FOLDER_A, existing.getFolderId());
        verify(noteMapper).updateById(existing);
    }

    @Test
    void moveNote_folderIdZero_meansUncategorized() {
        Note existing = note(NOTE_ID, USER_ID, FOLDER_A, "t", "c");
        when(noteMapper.selectById(NOTE_ID)).thenReturn(existing);

        service.moveNote(NOTE_ID, 0L);

        assertNull(existing.getFolderId());
        verify(noteMapper).updateById(existing);
        verify(noteFolderMapper, never()).selectById(anyLong());
    }

    @Test
    void deleteNote_removesOwnNote() {
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note(NOTE_ID, USER_ID, null, "t", "c"));

        service.deleteNote(NOTE_ID);

        verify(noteMapper).deleteById(NOTE_ID);
    }

    @Test
    void deleteNote_notOwner_throws() {
        when(noteMapper.selectById(NOTE_ID)).thenReturn(note(NOTE_ID, OTHER_USER_ID, null, "t", "c"));

        assertThrows(IllegalArgumentException.class, () -> service.deleteNote(NOTE_ID));
        verify(noteMapper, never()).deleteById(anyLong());
    }

    @Test
    void createNote_usesProvidedTitle() {
        doAnswer(invocation -> {
            Note saved = invocation.getArgument(0);
            saved.setNoteId(99L);
            return 1;
        }).when(noteMapper).insert(any(Note.class));

        NoteCreateDto dto = new NoteCreateDto();
        dto.setTitle("自定义标题");
        dto.setContent("正文");

        NoteDetailVo vo = service.createNote(dto);
        assertEquals("自定义标题", vo.getTitle());
        assertTrue(vo.getContent().contains("正文"));
    }
}
