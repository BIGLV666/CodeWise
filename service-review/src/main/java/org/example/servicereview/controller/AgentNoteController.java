package org.example.servicereview.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.NoteCreateDto;
import org.example.servicereview.dto.NoteFolderSaveDto;
import org.example.servicereview.dto.NoteUpdateDto;
import org.example.servicereview.service.NoteFolderService;
import org.example.servicereview.service.NoteService;
import org.example.servicereview.vo.NoteDetailVo;
import org.example.servicereview.vo.NoteFolderVo;
import org.example.servicereview.vo.NoteListVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * codewise-agent 专用笔记接口（与网页端 {@link NoteController} 分离成类）。
 *
 * <p>网页端接口使用 {@code /{noteId}} 路径参数，无法被 agent 工具的静态路径白名单精确匹配，
 * 故此处提供等价的 query 参数接口，薄委托同一组 service（NoteService / NoteFolderService），
 * 业务语义与归属校验完全一致。</p>
 */
@RestController
@RequestMapping("/api/review/agent/note")
public class AgentNoteController {

    @Autowired
    private NoteFolderService noteFolderService;
    @Autowired
    private NoteService noteService;

    /** 我的笔记文件夹列表（含笔记数）。 */
    @GetMapping("/folders")
    @RateLimit(limit = 100, window = 60)
    public Result<List<NoteFolderVo>> listFolders() {
        return Result.success(noteFolderService.listFolders());
    }

    /** 创建笔记文件夹。 */
    @PostMapping("/folders")
    @RateLimit(limit = 30, window = 60)
    public Result<NoteFolderVo> createFolder(@RequestBody NoteFolderSaveDto dto) {
        return Result.success(noteFolderService.createFolder(dto));
    }

    /** 重命名笔记文件夹。 */
    @PutMapping("/folders")
    @RateLimit(limit = 30, window = 60)
    public Result<NoteFolderVo> renameFolder(@RequestParam Long folderId, @RequestBody NoteFolderSaveDto dto) {
        return Result.success(noteFolderService.renameFolder(folderId, dto));
    }

    /** 删除笔记文件夹（级联删除其下笔记）。 */
    @DeleteMapping("/folders")
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteFolder(@RequestParam Long folderId) {
        noteFolderService.deleteFolder(folderId);
        return Result.success("删除成功");
    }

    /** 笔记列表（游标分页，folderId 缺省查全部）。 */
    @GetMapping("/list")
    @RateLimit(limit = 100, window = 60)
    public Result<NoteListVo> listNotes(@RequestParam(required = false) Long folderId,
                                        @RequestParam(required = false) Long cursor,
                                        @RequestParam(required = false) Integer size) {
        return Result.success(noteService.listNotes(folderId, cursor, size));
    }

    /** 笔记详情（全文）。 */
    @GetMapping("/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<NoteDetailVo> getNote(@RequestParam Long noteId) {
        return Result.success(noteService.getDetail(noteId));
    }

    /** 创建笔记。 */
    @PostMapping
    @RateLimit(limit = 30, window = 60)
    public Result<NoteDetailVo> createNote(@RequestBody NoteCreateDto dto) {
        return Result.success(noteService.createNote(dto));
    }

    /** 更新笔记标题/正文（内容类型不可变）。 */
    @PutMapping
    @RateLimit(limit = 30, window = 60)
    public Result<NoteDetailVo> updateNote(@RequestParam Long noteId, @RequestBody NoteUpdateDto dto) {
        return Result.success(noteService.updateNote(noteId, dto));
    }

    /** 移动笔记到文件夹（folderId 缺省表示移到未分类）。 */
    @PostMapping("/move")
    @RateLimit(limit = 30, window = 60)
    public Result<String> moveNote(@RequestParam Long noteId, @RequestParam(required = false) Long folderId) {
        noteService.moveNote(noteId, folderId);
        return Result.success("移动成功");
    }

    /** 删除笔记。 */
    @DeleteMapping
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteNote(@RequestParam Long noteId) {
        noteService.deleteNote(noteId);
        return Result.success("删除成功");
    }
}
