package org.example.servicequestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;


import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.servicequestion.entry.Question;
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
    void updateAiStatusToFailure(Long questionId);
    void updateAiStatusToSuccess(Long questionId);
    int updateTotal(Long questionId);
    int updateTotalAc(Long questionId);
    @Select("SELECT * FROM question WHERE title = #{title} LIMIT 1")
    Question selectByTitle(@Param("title") String title);
}
