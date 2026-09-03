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
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记管理接口：一级文件夹（分类）+ 其下 Markdown 笔记的增删改查。
 *
 * <p>身份安全：userId 一律取自网关注入的 {@code UserContext}，不接受客户端提交的用户标识；
 * 归属校验在 service 层完成。</p>
 */
@RestController
@RequestMapping("/api/review/note")
public class NoteController {

    @Autowired
    private NoteFolderService noteFolderService;
    @Autowired
    private NoteService noteService;

    // ==================== 文件夹 ====================

    /** 我的文件夹列表（含笔记数）。 */
    @GetMapping("/folders")
    @RateLimit(limit = 100, window = 60)
    public Result<List<NoteFolderVo>> listFolders() {
        return Result.success(noteFolderService.listFolders());
    }

    /** 创建文件夹。 */
    @PostMapping("/folders")
    @RateLimit(limit = 30, window = 60)
    public Result<NoteFolderVo> createFolder(@RequestBody NoteFolderSaveDto dto) {
        return Result.success(noteFolderService.createFolder(dto));
    }

    /** 重命名文件夹。 */
    @PutMapping("/folders/{folderId}")
    @RateLimit(limit = 30, window = 60)
    public Result<NoteFolderVo> renameFolder(@PathVariable Long folderId, @RequestBody NoteFolderSaveDto dto) {
        return Result.success(noteFolderService.renameFolder(folderId, dto));
    }

    /** 删除文件夹（级联删除其下笔记）。 */
    @DeleteMapping("/folders/{folderId}")
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteFolder(@PathVariable Long folderId) {
        noteFolderService.deleteFolder(folderId);
        return Result.success("删除成功");
    }

    // ==================== 笔记 ====================

    /**
     * 笔记列表（游标分页）。
     * <p>folderId 语义：缺省/空串→全部；正数→该文件夹；0→未分类（folder_id IS NULL）。</p>
     */
    @GetMapping("/list")
    @RateLimit(limit = 100, window = 60)
    public Result<NoteListVo> listNotes(@RequestParam(required = false) Long folderId,
                                        @RequestParam(required = false) Long cursor,
                                        @RequestParam(required = false) Integer size) {
        return Result.success(noteService.listNotes(folderId, cursor, size));
    }

    /** 笔记详情（全文）。 */
    @GetMapping("/{noteId}")
    @RateLimit(limit = 100, window = 60)
    public Result<NoteDetailVo> getNote(@PathVariable Long noteId) {
        return Result.success(noteService.getDetail(noteId));
    }

    /** 创建笔记。 */
    @PostMapping
    @RateLimit(limit = 30, window = 60)
    public Result<NoteDetailVo> createNote(@RequestBody NoteCreateDto dto) {
        return Result.success(noteService.createNote(dto));
    }

    /** 更新笔记标题/正文（内容类型不可变）。 */
    @PutMapping("/{noteId}")
    @RateLimit(limit = 30, window = 60)
    public Result<NoteDetailVo> updateNote(@PathVariable Long noteId, @RequestBody NoteUpdateDto dto) {
        return Result.success(noteService.updateNote(noteId, dto));
    }

    /** 移动笔记到文件夹（folderId 缺省/空串/0 表示移到未分类，正数表示目标文件夹）。 */
    @PostMapping("/{noteId}/move")
    @RateLimit(limit = 30, window = 60)
    public Result<String> moveNote(@PathVariable Long noteId, @RequestParam(required = false) Long folderId) {
        noteService.moveNote(noteId, folderId);
        return Result.success("移动成功");
    }

    /** 删除笔记。 */
    @DeleteMapping("/{noteId}")
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteNote(@PathVariable Long noteId) {
        noteService.deleteNote(noteId);
        return Result.success("删除成功");
    }
}
