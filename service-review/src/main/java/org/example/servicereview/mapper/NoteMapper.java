package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.servicereview.entry.Note;
import org.example.servicereview.vo.FolderNoteCountVo;

import java.util.List;

/**
 * 笔记 Mapper。
 */
@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /**
     * 游标分页查询笔记（按 note_id 倒序，新笔记在前）。
     *
     * <p>文件夹过滤三态：
     * <ul>
     *   <li>uncategorized=true：仅未分类（folder_id IS NULL）；</li>
     *   <li>folderId 非空：仅该文件夹（folder_id = folderId）；</li>
     *   <li>其余：全部（不过滤）。</li>
     * </ul>
     * 列表页只取正文前 600 字符用于生成预览，避免拉全量正文撑大响应。</p>
     *
     * @param userId        当前用户
     * @param folderId      文件夹 ID（可空；uncategorized=true 时忽略）
     * @param uncategorized 是否仅查未分类（folder_id IS NULL）
     * @param cursor        游标（上一页最后一条 note_id，可空表示第一页）
     * @param size          本次取条数
     * @return 笔记列表（正文为截断前缀）
     */
    @Select("""
            <script>
            SELECT note_id, user_id, folder_id, title,
                   LEFT(content, 600) AS content, content_type, create_time, update_time
            FROM note
            WHERE user_id = #{userId}
            <if test="uncategorized">AND folder_id IS NULL</if>
            <if test="folderId != null and !uncategorized">AND folder_id = #{folderId}</if>
            <if test="cursor != null">AND note_id &lt; #{cursor}</if>
            ORDER BY note_id DESC
            LIMIT #{size}
            </script>
            """)
    List<Note> selectPageByCursor(@Param("userId") Long userId,
                                  @Param("folderId") Long folderId,
                                  @Param("uncategorized") boolean uncategorized,
                                  @Param("cursor") Long cursor,
                                  @Param("size") int size);

    /**
     * 批量统计每个文件夹下的笔记数（含未分类 NULL 分组），避免文件夹列表接口 N+1。
     */
    @Select("""
            SELECT folder_id AS folderId, COUNT(*) AS noteCount
            FROM note
            WHERE user_id = #{userId}
            GROUP BY folder_id
            """)
    List<FolderNoteCountVo> countByFolder(@Param("userId") Long userId);
}
