package org.example.servicejudge.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.servicejudge.entry.Question;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
