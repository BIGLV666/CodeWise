package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.until.convert.GroupSubmitRecordConvert;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class SubmitRecordService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private GroupSubmitRecordMapper groupSubmitRecordMapper;
    @Autowired
    private UserFeignClient userFeignClient;
    @Autowired
    private GroupQuestionPermissionService groupQuestionPermissionService;

    public SubmitRecord getSubmitRecordById(Long submitRecordId) {
        if (submitRecordId == null) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_ID_REQUIRED);
        }
        SubmitRecord submitRecord = submitRecordMapper.selectOne(
                new LambdaQueryWrapper<SubmitRecord>()
                        .eq(SubmitRecord::getSubmitRecordId, submitRecordId)
                        .eq(SubmitRecord::getUserId, UserContext.getUserId())
        );
        if (submitRecord == null) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_NOT_FOUND);
        }
        return submitRecord;
    }

    public List<SubmitRecord> getSubmitRecordsByIds(List<Long> submitRecordIds) {
        if (submitRecordIds == null || submitRecordIds.isEmpty()) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_LIST_REQUIRED);
        }

        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(submitRecordIds));
        if (distinctIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_ID_INVALID);
        }
        if (distinctIds.size() > 10) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_BATCH_LIMIT, 10);
        }

        return submitRecordMapper.selectList(
                new LambdaQueryWrapper<SubmitRecord>()
                        .in(SubmitRecord::getSubmitRecordId, distinctIds)
                        .eq(SubmitRecord::getUserId, UserContext.getUserId())
                        .orderByDesc(SubmitRecord::getSubmitTime)
        );
    }

    @Transactional
    public void deleteSubmitRecord(Long submitRecordId) {

        Long userId = UserContext.getUserId();
        if(userId==null){
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        int r=submitRecordMapper.delete(new QueryWrapper<SubmitRecord>().eq("submit_record_id",submitRecordId).eq("user_id",userId));
        if(r==0){
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_NOT_FOUND);
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
        wrapper.eq(SubmitRecord::getUserId, UserContext.getUserId());

        // 按 ID 倒序分页，下一页继续查询小于上一页末尾 ID 的记录。
        if (lastId != null) {
            wrapper.lt(SubmitRecord::getSubmitRecordId, lastId);
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
                nextCursor = lastRecord.getSubmitRecordId();
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


    /**
     * 小组判题记录游标分页：不区分题目，按 {@code group_submit_record_id} 倒序
     * （自增主键与提交时间同序）。
     *
     * <p>一次查询小组索引，再把本页 {@code submit_record} 与用户信息各批量拉取一次，
     * 避免 N+1。游标取本页最后一条「已消费」索引；提交记录缺失的孤儿索引只跳过
     * 输出、不阻断翻页。</p>
     *
     * @param groupId  小组 id
     * @param lastId   上一页 {@code nextCursor}，首页为空
     * @param pageSize 每页条数，为空/非正数取 20，超过 50 截断
     * @return 小组提交记录分页
     */
    public CursorPageResult<MiniGroupSubmitRecordVo> cursorMiniGroupSubmitRecord(
            Long groupId, Long lastId, Integer pageSize) {
        groupQuestionPermissionService.assertApprovedGroupMember(groupId);

        int limit = normalizePageSize(pageSize);
        LambdaQueryWrapper<GroupSubmitRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupSubmitRecord::getGroupId, groupId)
                .orderByDesc(GroupSubmitRecord::getGroupSubmitRecordId)
                .last("LIMIT " + (limit + 1));
        if (lastId != null && lastId > 0) {
            wrapper.lt(GroupSubmitRecord::getGroupSubmitRecordId, lastId);
        }

        List<GroupSubmitRecord> rows = groupSubmitRecordMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return CursorPageResult.<MiniGroupSubmitRecordVo>builder()
                    .records(Collections.emptyList())
                    .nextCursor(null)
                    .hasNext(false)
                    .build();
        }

        boolean hasNext = rows.size() > limit;
        List<GroupSubmitRecord> pageRows = hasNext
                ? new ArrayList<>(rows.subList(0, limit))
                : rows;
        // 游标取本页最后一条已消费索引：即使它的 submit_record 缺失，也不会让翻页停滞
        Long nextCursor = hasNext ? pageRows.getLast().getGroupSubmitRecordId() : null;

        List<Long> submitRecordIds = pageRows.stream()
                .map(GroupSubmitRecord::getSubmitRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SubmitRecord> submitRecordMap = loadSubmitRecords(submitRecordIds);

        // 孤儿索引（submit_record 已不存在）只跳过输出；用户信息也只查有效记录，
        // 避免为脏数据发起无意义的批量用户查询
        List<GroupSubmitRecord> visibleRows = new ArrayList<>(pageRows.size());
        for (GroupSubmitRecord row : pageRows) {
            if (submitRecordMap.containsKey(row.getSubmitRecordId())) {
                visibleRows.add(row);
            } else {
                log.warn("小组提交索引缺少提交记录: groupSubmitRecordId={}, submitRecordId={}",
                        row.getGroupSubmitRecordId(), row.getSubmitRecordId());
            }
        }

        List<Long> userIds = visibleRows.stream()
                .map(GroupSubmitRecord::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, UserDto> userMap = loadUsers(userIds);

        List<MiniGroupSubmitRecordVo> records = visibleRows.stream()
                .map(row -> GroupSubmitRecordConvert.toMiniGroupSubmitRecordVo(
                        row,
                        submitRecordMap.get(row.getSubmitRecordId()),
                        userMap.get(row.getUserId())))
                .toList();

        return CursorPageResult.<MiniGroupSubmitRecordVo>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    /**
     * 同组成员查看具体提交记录
     * <B>只需要校验该用户为小组成员或者该用户即为创作者本身</B>
     * @param groupQuestionId 小组记录索引
     */

    public SubmitRecord getGroupSubmitRecord(Long groupQuestionId){
        GroupSubmitRecord groupSubmitRecord = groupSubmitRecordMapper.selectById(groupQuestionId);
        if (groupSubmitRecord == null) throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
        //先查询记录，如果先鉴权会把退出小组的提交者本身拦住
        SubmitRecord submitRecord=submitRecordMapper.selectById(groupSubmitRecord.getSubmitRecordId());
        if(submitRecord==null) throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
        if(submitRecord.getUserId().equals(UserContext.getUserId())){
            return submitRecord;
        }
        groupQuestionPermissionService.assertApprovedGroupMember(groupSubmitRecord.getGroupId());
        return submitRecord;

    }

    private Map<Long, SubmitRecord> loadSubmitRecords(List<Long> submitRecordIds) {
        if (submitRecordIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SubmitRecord> submitRecords = submitRecordMapper.selectBatchIds(submitRecordIds);
        if (submitRecords == null || submitRecords.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, SubmitRecord> map = new HashMap<>(submitRecords.size());
        for (SubmitRecord submitRecord : submitRecords) {
            if (submitRecord.getSubmitRecordId() != null) {
                map.put(submitRecord.getSubmitRecordId(), submitRecord);
            }
        }
        return map;
    }

    /**
     * 批量拉取用户信息；用户服务不可用时显式失败，避免列表出现整页空白昵称。
     * 用户已被删除（返回 map 中缺失）时由转换器降级为 null 昵称/头像。
     */
    private Map<Long, UserDto> loadUsers(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Result<Map<Long, UserDto>> body;
        try {
            body = userFeignClient.getUserList(userIds);
        } catch (Exception e) {
            log.error("批量查询用户信息失败: userIds={}", userIds, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if (body == null || body.getCode() == null || body.getCode() != 200) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        return body.getData() == null ? Collections.emptyMap() : body.getData();
    }

    static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
