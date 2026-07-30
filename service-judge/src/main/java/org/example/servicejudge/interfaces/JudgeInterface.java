package org.example.servicejudge.interfaces;

import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;

import java.io.IOException;
import java.util.List;

public interface JudgeInterface {
    JudgeReturnDto executeCode(String code, String language, String input) ;
    JudgeRecord batchExecuteCode(String code, String language, List<TestDto> testCases) throws IOException;

    JudgeRecord batchExecuteCode(String code, String mainCode, String language, List<TestDto> testCases) throws IOException;

    List<JudgeRecord> batchDebugCode(String code, String mainCode, String language, List<TestDto> testCases) throws IOException;

}
