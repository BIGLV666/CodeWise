package org.example.servicejudge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicejudge.entry.TestCase;

import java.util.List;
import java.util.Map;

@Mapper
public interface TestCaseMapper extends BaseMapper<TestCase> {
    long deleteByQuestionIds(List<Long> questionIds);
    List<Map<String,Object>> countTestCasesByQuestionId();
    @Delete("DELETE FROM test_case WHERE question_id = #{questionId}")
    void deleteByQuestionId(@Param("questionId") Long questionId);
}
