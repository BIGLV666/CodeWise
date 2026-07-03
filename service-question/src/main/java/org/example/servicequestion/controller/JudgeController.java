package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.service.JudgeService;
import org.example.servicequestion.service.SubmitRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/question")
public class JudgeController {

    @Autowired
    private JudgeService judgeService;

    @Autowired
    private SubmitRecordService submitRecordService;

    @PostMapping("/judge")
    public Result<Long> judge(@RequestBody GetCodeDto getCodeDto) {
        Long submitRecordId = judgeService.judge(getCodeDto.getCode(), getCodeDto.getLanguage(), getCodeDto.getQuestionId());
        return Result.success(submitRecordId);
    }

    @GetMapping("/getsubmitrecordbyid")
    public Result<SubmitRecord> getSubmitRecordById(@RequestParam Long submitRecordId) {
        SubmitRecord submitRecord = submitRecordService.getSubmitRecordById(submitRecordId);
        return Result.success(submitRecord);
    }

    @GetMapping("/getsubmitrecordsbyquestionid")
    public Result<List<SubmitRecord>> getSubmitRecordsByQuestionId(@RequestParam Long questionId) {
        List<SubmitRecord> records = submitRecordService.getSubmitRecordsByQuestionId(questionId);
        return Result.success(records);
    }

    @GetMapping("/getsubmitrecordsbyuserid")
    public Result<List<SubmitRecord>> getSubmitRecordsByUserId() {
        List<SubmitRecord> records = submitRecordService.getSubmitRecordsByUserId(UserContext.getUserId());
        return Result.success(records);
    }

    @DeleteMapping("/deletesubmitrecord")
    public Result<Void> deleteSubmitRecord(@RequestParam Long submitRecordId) {
        submitRecordService.deleteSubmitRecord(submitRecordId);
        return Result.success("删除成功");
    }
}
