package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.FunctionTestCaseMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TestBuildForAiService {
    @Autowired
    private FunctionTestCaseMapper functionTestCaseMapper;
    @Autowired
    private QuestionMapper questionMapper;

}
