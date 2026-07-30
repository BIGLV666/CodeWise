package org.example.servicejudge.Dto;

import lombok.Builder;
import lombok.Data;
import org.example.servicejudge.entry.FunctionTestCase;
import org.example.servicejudge.entry.TestCase;

@Data
public class TestDto {
    private Long caseId;

    private Long questionId;

    private String inputData;

    private String expectedOutput;
    public  TestDto(TestCase testCase){
        this.caseId=testCase.getCaseId();
        this.questionId=testCase.getQuestionId();
        this.expectedOutput=testCase.getExpectedOutput();
        this.inputData=testCase.getInputData();
    }
    public TestDto(FunctionTestCase testCase){
        this.caseId= testCase.getFunctionTestCaseId();
        this.questionId= testCase.getQuestionId();
        this.expectedOutput=testCase.getExpectedOutput();
        this.inputData=testCase.getInputData();

    }



}
