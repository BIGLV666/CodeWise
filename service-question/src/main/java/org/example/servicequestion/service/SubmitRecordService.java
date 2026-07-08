package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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


    public CursorPageResult<SubmitRecord> cursorSubmitRecord(Long lastId, Integer pageSize) {
        LambdaQueryWrapper<SubmitRecord> wrapper = new LambdaQueryWrapper<>();

        // 游标分页：如果传入了 lastId，则查询大于 lastId 的记录（升序）
        if (lastId != null) {
            wrapper.gt(SubmitRecord::getSubmitRecordId, lastId);  // 改为 gt（大于），从前往后
        }

//        // 难度筛选
//        if (difficulty != null) {
//            wrapper.eq(Question::getDifficulty, difficulty);
//        }

        // 按 questionId 升序排列（从小到大）
        wrapper.orderByDesc(SubmitRecord::getSubmitRecordId);  // 改为升序
        wrapper.last("LIMIT " + (pageSize + 1));

        List<SubmitRecord> list = submitRecordMapper.selectList(wrapper);

        List<SubmitRecord> records = new ArrayList<>();
        Long nextCursor = null;
        Boolean hasNext = false;

        if (list != null && !list.isEmpty()) {
            if (list.size() > pageSize) {
                // 取前 pageSize 条作为当前页
                records = list.subList(0, pageSize);
                // 获取最后一条记录的 ID 作为下一页的游标
                SubmitRecord lastRecord = records.getLast();
                nextCursor = lastRecord.getQuestionId();
                hasNext = true;
            } else {
                records = list;
                hasNext = false;
            }
        }

        return CursorPageResult.<SubmitRecord>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
