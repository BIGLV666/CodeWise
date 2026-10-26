package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.service.JudgeService;
import org.example.servicequestion.service.SubmitRecordService;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import io.github.biglv666.apigovernance.annotation.RateLimit;

import java.util.List;

@RestController
@RequestMapping("/api/question")
public class JudgeController {

    @Autowired
    private JudgeService judgeService;

    @Autowired
    private SubmitRecordService submitRecordService;

    @PostMapping("/judge")
    @RateLimit(limit = 120, window = 60)
    public Result<Long> judge(@RequestBody GetCodeDto getCodeDto) {
        Long submitRecordId = judgeService.judge(getCodeDto);
        return Result.success(submitRecordId);
    }
    @PostMapping("/debug")
    @RateLimit(limit = 5, window = 60)
    public Result<String> debug(@RequestBody DebugDto getCodeDto) {
        return Result.success(judgeService.debug(getCodeDto));
    }

    @GetMapping("/getsubmitrecordbyid")
    @RateLimit(limit = 200, window = 60)
    public Result<SubmitRecord> getSubmitRecordById(@RequestParam Long submitRecordId) {
        SubmitRecord submitRecord = submitRecordService.getSubmitRecordById(submitRecordId);
        return Result.success(submitRecord);
    }

    @PostMapping("/getsubmitrecordsbyids")
    @RateLimit(limit = 100, window = 60)
    public Result<List<SubmitRecord>> getSubmitRecordsByIds(@RequestBody List<Long> submitRecordIds) {
        return Result.success(submitRecordService.getSubmitRecordsByIds(submitRecordIds));
    }

    @GetMapping("/getsubmitrecordsbyquestionid")
    @RateLimit(limit = 100, window = 60)
    public Result<List<SubmitRecord>> getSubmitRecordsByQuestionId(@RequestParam Long questionId) {
        List<SubmitRecord> records = submitRecordService.getSubmitRecordsByQuestionId(questionId);
        return Result.success(records);
    }


    @GetMapping("/getsubmitrecordsbyuserid")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<SubmitRecord>> getSubmitRecordsByUserId(
            @RequestParam(required = false) Long lastId,
            @RequestParam Integer pageSize) {

            CursorPageResult<SubmitRecord> records = submitRecordService.cursorSubmitRecord(lastId,pageSize);
        return Result.success(records);
    }

    /**
     * 小组提交记录游标分页：不区分题目，按提交时间（自增 id）倒序，仅本组 APPROVED 成员可看。
     *
     * @param groupId  小组 id
     * @param lastId   上一页 nextCursor，首页不传
     * @param pageSize 每页条数，默认 20，上限 50
     */
    @GetMapping("/group/{groupId}/submit-records")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<MiniGroupSubmitRecordVo>> cursorMiniGroupSubmitRecords(
            @PathVariable Long groupId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(required = false) Integer pageSize) {
        return Result.success(submitRecordService.cursorMiniGroupSubmitRecord(groupId, lastId, pageSize));
    }

    @DeleteMapping("/deletesubmitrecord")
    @RateLimit(limit = 30, window = 60)
    public Result<Void> deleteSubmitRecord(@RequestParam Long submitRecordId) {
        submitRecordService.deleteSubmitRecord(submitRecordId);
        return Result.success("删除成功");
    }
}
