package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SubmitRecordService {

    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Transactional
    public SubmitRecord addSubmitRecord(SubmitRecord submitRecord) {
        if (submitRecordMapper.insert(submitRecord) <= 0) {
            throw new RuntimeException("添加提交记录失败");
        }
        return submitRecord;
    }

    public SubmitRecord getSubmitRecordById(Long submitRecordId) {
        SubmitRecord submitRecord = submitRecordMapper.selectById(submitRecordId);
        if (submitRecord == null) {
            throw new RuntimeException("提交记录不存在");
        }
        return submitRecord;
    }

    @Transactional
    public SubmitRecord updateSubmitRecord(SubmitRecord submitRecord) {
        if (submitRecord.getSubmitRecordId() == null) {
            throw new RuntimeException("提交记录ID不能为空");
        }
        if (submitRecordMapper.updateById(submitRecord) <= 0) {
            throw new RuntimeException("更新提交记录失败");
        }
        return submitRecordMapper.selectById(submitRecord.getSubmitRecordId());
    }

    @Transactional
    public void deleteSubmitRecord(Long submitRecordId) {
        if (submitRecordMapper.deleteById(submitRecordId) <= 0) {
            throw new RuntimeException("删除提交记录失败");
        }
    }

    public List<SubmitRecord> getSubmitRecordsByQuestionId(Long questionId) {
        return submitRecordMapper.selectList(new QueryWrapper<SubmitRecord>()
                .eq("user_id", UserContext.getUserId())
                .eq("question_id", questionId)
                .orderByDesc("submit_time"));
    }

    public List<SubmitRecord> getSubmitRecordsByUserId(Long userId) {
        return submitRecordMapper.selectList(new QueryWrapper<SubmitRecord>()
                .eq("user_id", userId)
                .orderByDesc("submit_time"));
    }
}
