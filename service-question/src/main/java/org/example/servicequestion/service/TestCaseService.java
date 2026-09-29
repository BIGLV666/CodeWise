package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.InsertTestCaseDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TestCaseService {
    @Autowired
    private TestCaseMapper testCaseMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private UserFeignClient userFeignClient;

    public List<Map<String,Object>> getAllTestCase(){

        List<Map<String,Object>>s= testCaseMapper.countTestCasesByQuestionId();
        Map<String,Object>map=new HashMap<>();
        map.put("total",s.size());
        List<Long>qs=new ArrayList<>();
        for(Map<String,Object>map1:s){
            qs.add((Long)map1.get("question_id"));
        }
        qs.sort(Comparator.comparing(Long::intValue));
        map.put("questions",qs);
        s.add(map);
        return s;
    }

    @Transactional
    public TestCase addTestCase(TestCase testCase) {
        checkQuestionPermission(testCase.getQuestionId());
        testCase.setCaseId(null);
        testCase.setCreateUserId(UserContext.getUserId());
        int insert = testCaseMapper.insert(testCase);
        if (insert > 0) {
            return testCase;
        }
        throw new BizException(QuestionErrorCode.TESTCASE_SAVE_FAILED);
    }

    public TestCase getTestCaseById(Long caseId) {
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null) {
            throw new BizException(QuestionErrorCode.TESTCASE_NOT_FOUND);
        }
        checkQuestionPermission(testCase.getQuestionId());
        return testCase;
    }

    public List<TestCase> getTestCasesByQuestionId(Long questionId) {
        checkQuestionPermission(questionId);
        return testCaseMapper.selectList(
                new QueryWrapper<TestCase>().eq("question_id", questionId).orderByAsc("sort_order")
        );
    }

    @Transactional
    public TestCase updateTestCase(InsertTestCaseDto dto, Long caseId) {
        TestCase existing = testCaseMapper.selectById(caseId);
        if (existing == null) {
            throw new BizException(QuestionErrorCode.TESTCASE_NOT_FOUND);
        }
        checkQuestionPermission(existing.getQuestionId());
        if (dto.getInputData() != null) {
            existing.setInputData(dto.getInputData());
        }
        if (dto.getExpectedOutput() != null) {
            existing.setExpectedOutput(dto.getExpectedOutput());
        }
        if (dto.getIsSample() != null) {
            existing.setIsSample(dto.getIsSample());
        }
        if (dto.getIsHidden() != null) {
            existing.setIsHidden(dto.getIsHidden());
        }
        if (dto.getSortOrder() != null) {
            existing.setSortOrder(dto.getSortOrder());
        }
        if (dto.getScoreWeight() != null) {
            existing.setScoreWeight(dto.getScoreWeight());
        }
        if (dto.getTimeLimit() != null) {
            existing.setTimeLimit(dto.getTimeLimit());
        }
        if (dto.getMemoryLimit() != null) {
            existing.setMemoryLimit(dto.getMemoryLimit());
        }
        int update = testCaseMapper.updateById(existing);
        if (update > 0) {
            return existing;
        }
        throw new BizException(QuestionErrorCode.TESTCASE_UPDATE_FAILED);
    }

    @Transactional
    public void deleteTestCase(Long caseId) {
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null) {
            throw new BizException(QuestionErrorCode.TESTCASE_NOT_FOUND);
        }
        checkQuestionPermission(testCase.getQuestionId());
        int delete = testCaseMapper.deleteById(caseId);
        if (delete <= 0) {
            throw new BizException(QuestionErrorCode.TESTCASE_DELETE_FAILED);
        }
    }

    private void checkQuestionPermission(Long questionId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        Result<UserDto> userResult = userFeignClient.getUserInfo(userId);
        if (userResult.getCode() != 200) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (userResult.getData() == null) {
            throw new BizException(QuestionErrorCode.USER_NOT_FOUND);
        }
        if (!userId.equals(question.getCreateUserId()) && !userResult.getData().getRoleId().equals(2)) {
            throw new BizException(CommonErrorCode.NO_PERMISSION);
        }
    }
}
