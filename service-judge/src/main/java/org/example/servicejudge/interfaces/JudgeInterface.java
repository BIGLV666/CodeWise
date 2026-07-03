package org.example.servicejudge.interfaces;

import org.example.serviceapi.dto.JudgeResultDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;

public interface JudgeInterface {
    JudgeRecord executeCode(String code, String language, TestCase testMessage) ;
}
