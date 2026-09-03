package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.servicereview.entry.NoteFolder;

/**
 * 笔记文件夹 Mapper。
 */
@Mapper
public interface NoteFolderMapper extends BaseMapper<NoteFolder> {
}
