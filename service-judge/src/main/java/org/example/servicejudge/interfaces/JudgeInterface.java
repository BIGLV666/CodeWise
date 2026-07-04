package org.example.servicejudge.interfaces;

import org.example.serviceapi.dto.JudgeResultDto;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;

public interface JudgeInterface {
    JudgeReturnDto executeCode(String code, String language, String input) ;
}
