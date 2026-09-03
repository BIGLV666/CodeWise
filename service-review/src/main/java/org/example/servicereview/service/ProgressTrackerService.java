package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.ProgressTrackerDto;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.enums.ProgressTrackerStatus;
import org.example.servicereview.mapper.ProgressTrackerMapper;
import org.example.servicereview.vo.ProgressBatchCreateVo;
import org.example.servicereview.vo.ProgressBatchDeleteVo;
import org.example.servicereview.vo.ProgressCalendarVo;
import org.example.servicereview.vo.ProgressTrackerBriefVo;
import org.example.servicereview.vo.ReportCompletedItemVo;
import org.example.servicereview.vo.WeeklyReportVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ProgressTrackerService {

    /** 计划判题消息最大重试次数，超限后转入失败登记（与复习消费的重试策略对齐）。 */
    private static final int PLAN_MAX_RETRY = 3;
    /** 判定 AC 的状态值，与 submit_status 语义一致。 */
    private static final String STATUS_AC = "AC";

    @Autowired
    private ProgressTrackerMapper progressTrackerMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private QuestionFeignClient questionFeignClient;

    // ==================== 消息消费：判题结果绑定当天计划 ====================

    /**
     * 接收 PLAN 场景判题结果，绑定到用户当天的进度计划。
     * <p>
     * 监听独立队列 {@code reviews.plan.queue}（路由键 plan.judge.record.routing），
     * 与复习消费分队列——同一队列挂两个监听会形成竞争消费者，各自 ACK 掉
     * 不匹配的消息导致丢失。
     * </p>
     * <p>绑定规则：submitRecordId 追加进当天计划的 submit_ids（去重），
     * 判题结果为 AC 时计划置为已完成；无当天计划则跳过（可能已删除/过期未重新规划）。</p>
     */
    @RabbitListener(queues = MqContexts.REVIEW_PLAN_QUEUE_NAME)
    public void setPlan(Message amqpMessage, Channel channel,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) throws IOException {
        long tag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            ReviewJudgeRecordDto record = EnvelopeCodec.unwrap(
                    new String(amqpMessage.getBody(), StandardCharsets.UTF_8), ReviewJudgeRecordDto.class);
            if (record == null || record.getUserId() == null || record.getQuestionId() == null
                    || record.getSubmitRecordId() == null) {
                channel.basicNack(tag, false, false);
                log.error("计划判题消息缺少必要字段，丢弃, routingKey={}", routingKey);
                return;
            }
            bindJudgeRecord(record);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("计划判题消息处理失败, routingKey={}", routingKey, e);
            try {
                String retryId = "plan:" + DigestUtils.md5DigestAsHex(amqpMessage.getBody());
                Long retryCount = redisTemplate.opsForHash().increment(
                        RedisContext.REVIEW_JUDGE_RETRY_COUNT_KEY, retryId, 1);
                boolean exhausted = retryCount != null && retryCount >= PLAN_MAX_RETRY;
                if (exhausted) {
                    redisTemplate.opsForHash().put(
                            RedisContext.REVIEW_JUDGE_FAILED_KEY,
                            retryId,
                            new String(amqpMessage.getBody(), StandardCharsets.UTF_8));
                }
                channel.basicNack(tag, false, !exhausted);
            } catch (Exception nackException) {
                log.error("计划判题消息失败处理异常", nackException);
            }
        }
    }

    /**
     * 绑定判题结果到当天计划。用户级 + 日期分布式锁串行化同一计划的并发绑定，
     * 避免 JSON 列表的读改写丢失更新；submitRecordId 去重保证重复投递幂等。
     */
    private void bindJudgeRecord(ReviewJudgeRecordDto record) throws InterruptedException {
        String lockKey = "review:plan:" + record.getUserId() + ":" + LocalDate.now();
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("计划正在更新，请稍后重试");
        }
        try {
            ProgressTracker plan = progressTrackerMapper.selectOne(new QueryWrapper<ProgressTracker>()
                    .eq("user_id", record.getUserId())
                    .eq("question_id", record.getQuestionId())
                    .eq("begin_time", LocalDate.now()));
            if (plan == null) {
                log.info("无当天计划，跳过绑定, userId={}, questionId={}, submitRecordId={}",
                        record.getUserId(), record.getQuestionId(), record.getSubmitRecordId());
                return;
            }
            List<Long> submitIds = plan.getSubmitIds() == null
                    ? new ArrayList<>() : new ArrayList<>(plan.getSubmitIds());
            if (!submitIds.contains(record.getSubmitRecordId())) {
                submitIds.add(record.getSubmitRecordId());
                plan.setSubmitIds(submitIds);
            }
            if (STATUS_AC.equals(record.getStatus())) {
                plan.setStatus(ProgressTrackerStatus.COMPLETED.getCode());
            }
            plan.setUpdateTime(LocalDateTime.now());
            progressTrackerMapper.updateById(plan);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 计划 CRUD ====================

    public ProgressTracker createProgressTracker(ProgressTrackerDto dto){
        ProgressTracker progressTracker=new ProgressTracker(dto);
        progressTracker.setUserId(UserContext.getUserId());
        progressTracker.setCreateTime(LocalDateTime.now());
        progressTracker.setStatus(resolveInitialStatus(progressTracker.getBeginTime()));
        try{
           int r= progressTrackerMapper.insert(progressTracker);
           if(r==0){
               throw new RuntimeException("创建进度计划失败");
           }
        }catch (Exception e){
            if(e instanceof DuplicateKeyException){
                throw new RuntimeException("该题目已存在进度计划");
            }
            throw new RuntimeException("创建进度计划失败");
        }
        return progressTracker;
    }

    /**
     * 按日期精确查询当天计划，返回前先做状态懒刷新。
     * 前端日期列表点击某天后的详情入口。
     */
    public List<ProgressTracker> listByDate(LocalDate date) {
        Long userId = UserContext.getUserId();
        List<ProgressTracker> list = progressTrackerMapper.selectList(
                new QueryWrapper<ProgressTracker>()
                        .eq("user_id", userId)
                        .eq("begin_time", date)
                        .orderByAsc("progress_id"));
        lazyRefreshStatus(list);
        return list;
    }

    /** 单日聚合：该日期的计划题数与完成数。 */
    public List<ProgressCalendarVo> calendarDay(LocalDate date) {
        return fillCalendar(date, date,
                progressTrackerMapper.aggregateByDateRange(UserContext.getUserId(), date, date));
    }

    /** 周聚合：锚点日期所在自然周（周一至周日）共 7 天。 */
    public List<ProgressCalendarVo> calendarWeek(LocalDate anchor) {
        LocalDate start = anchor.with(DayOfWeek.MONDAY);
        return fillCalendar(start, start.plusDays(6),
                progressTrackerMapper.aggregateByDateRange(UserContext.getUserId(), start, start.plusDays(6)));
    }

    /** 月聚合：锚点日期所在自然月，天数随月份变化（28-31），不固定。 */
    public List<ProgressCalendarVo> calendarMonth(LocalDate anchor) {
        LocalDate start = anchor.withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        return fillCalendar(start, end,
                progressTrackerMapper.aggregateByDateRange(UserContext.getUserId(), start, end));
    }

    /**
     * 更新计划：备注/总结任何状态都可改；开始时间仅在「未开始」时可改（单向状态机）。
     *
     * <p>状态不可逆向：未开始 → 进行中 →（已过期 或 已完成）。已过期/已完成只锁开始时间，
     * 备注与反思仍可修改；已过期要重新安排只能删除后新建（或直接删除）。</p>
     */
    public void updateProgress(Long progressId, LocalDate beginTime, String notesContent, String summaryContent) {
        ProgressTracker existing = getOwnProgressOrThrow(progressId);
        if (beginTime != null && !beginTime.equals(existing.getBeginTime())) {
            // 单向状态机：仅「未开始」允许改开始时间；进行中/已过期/已完成一律锁定
            if (!Integer.valueOf(ProgressTrackerStatus.NOT_STARTED.getCode()).equals(existing.getStatus())) {
                throw new IllegalArgumentException("仅未开始的计划可修改开始时间");
            }
            if (beginTime.isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("开始时间不可早于当前时间");
            }
            existing.setBeginTime(beginTime);
            // 重新规划后按新日期重算状态（改到今天→进行中，仍在未来→未开始）
            existing.setStatus(resolveInitialStatus(beginTime));
        }
        if (notesContent != null) {
            existing.setNotesContent(notesContent);
        }
        if (summaryContent != null) {
            existing.setSummaryContent(summaryContent);
        }
        existing.setUpdateTime(LocalDateTime.now());
        progressTrackerMapper.updateById(existing);
    }

    /** 删除自己的计划。 */
    public void deleteProgress(Long progressId) {
        getOwnProgressOrThrow(progressId);
        progressTrackerMapper.deleteById(progressId);
    }

    // ==================== agent 专用 / 周报聚合 ====================

    /** 单批最多创建/删除的计划条数上限。 */
    private static final int MAX_PROGRESS_BATCH = 200;
    /** 列表/今日计划里备注与反思的截断长度；完整内容走详情接口。 */
    private static final int BRIEF_TEXT_LIMIT = 100;

    /** 今天要做的计划（瘦身，含题目标题/难度回填，状态懒刷新）。 */
    public List<ProgressTrackerBriefVo> listToday() {
        return toBriefVos(listByDate(LocalDate.now()));
    }

    /** 未开始 + 进行中的计划（即「接下来要做」），按日期升序，瘦身返回。 */
    public List<ProgressTrackerBriefVo> listActive() {
        Long userId = UserContext.getUserId();
        List<ProgressTracker> list = progressTrackerMapper.selectList(new QueryWrapper<ProgressTracker>()
                .eq("user_id", userId)
                .in("status", ProgressTrackerStatus.NOT_STARTED.getCode(), ProgressTrackerStatus.IN_PROGRESS.getCode())
                .orderByAsc("begin_time")
                .orderByAsc("progress_id"));
        lazyRefreshStatus(list);
        return toBriefVos(list);
    }

    /** 单条计划详情（含 submitIds 与完整备注/反思；返回前懒刷新状态）。 */
    public ProgressTracker getDetail(Long progressId) {
        ProgressTracker plan = getOwnProgressOrThrow(progressId);
        lazyRefreshStatus(List.of(plan));
        return plan;
    }

    /**
     * 批量创建进度计划（agent 专用，幂等）。
     *
     * <p>与网页端单条创建的区别：已存在同题计划时记为 skipped 而非报错，
     * 天然支持重试；单批上限 {@link #MAX_PROGRESS_BATCH}。</p>
     */
    public ProgressBatchCreateVo batchCreate(List<ProgressTrackerDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            throw new IllegalArgumentException("计划列表不能为空");
        }
        if (dtos.size() > MAX_PROGRESS_BATCH) {
            throw new IllegalArgumentException("单次最多创建 " + MAX_PROGRESS_BATCH + " 条计划");
        }
        Long userId = UserContext.getUserId();
        int created = 0;
        List<Long> skipped = new ArrayList<>();
        for (ProgressTrackerDto dto : dtos) {
            // 构造器校验 questionId/beginTime 必填、开始时间不得早于今天（单向状态机入口约束）
            ProgressTracker plan = new ProgressTracker(dto);
            plan.setUserId(userId);
            plan.setCreateTime(LocalDateTime.now());
            plan.setStatus(resolveInitialStatus(plan.getBeginTime()));
            try {
                int r = progressTrackerMapper.insert(plan);
                if (r == 0) {
                    throw new RuntimeException("创建进度计划失败");
                }
                created++;
            } catch (DuplicateKeyException e) {
                skipped.add(dto.getQuestionId());
            }
        }
        return ProgressBatchCreateVo.builder().created(created).skippedQuestionIds(skipped).build();
    }

    /**
     * 批量删除自己的计划（agent 专用）。
     *
     * <p>不存在或非本人的 ID 记为 notFound，不外泄他人计划是否存在。</p>
     */
    public ProgressBatchDeleteVo batchDelete(List<Long> progressIds) {
        List<Long> distinct = progressIds == null ? List.of()
                : progressIds.stream().filter(id -> id != null).distinct().toList();
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("计划 ID 列表不能为空");
        }
        if (distinct.size() > MAX_PROGRESS_BATCH) {
            throw new IllegalArgumentException("单次最多删除 " + MAX_PROGRESS_BATCH + " 条计划");
        }
        Long userId = UserContext.getUserId();
        int deleted = 0;
        List<Long> notFound = new ArrayList<>();
        for (Long id : distinct) {
            ProgressTracker plan = progressTrackerMapper.selectById(id);
            if (plan == null || !plan.getUserId().equals(userId)) {
                notFound.add(id);
                continue;
            }
            progressTrackerMapper.deleteById(id);
            deleted++;
        }
        return ProgressBatchDeleteVo.builder().deleted(deleted).notFoundIds(notFound).build();
    }

    /**
     * 周报：锚点日期所在自然周（周一至周日）的计划汇总。
     *
     * <p>读时聚合，不落统计表：本周计划/完成/进行中/过期计数 + 逐日分布 + 已完成条目
     * （含反思，标题经题目瘦身 Feign 批量回填）。供前端渲染与 agent 组织总结。</p>
     */
    public WeeklyReportVo getWeeklyReport(LocalDate anchor) {
        LocalDate today = anchor != null ? anchor : LocalDate.now();
        LocalDate start = today.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        Long userId = UserContext.getUserId();
        List<ProgressTracker> rows = progressTrackerMapper.selectList(new QueryWrapper<ProgressTracker>()
                .eq("user_id", userId)
                .ge("begin_time", start)
                .le("begin_time", end));
        lazyRefreshStatus(rows);

        long completed = 0;
        long inProgress = 0;
        long expired = 0;
        Map<LocalDate, ProgressCalendarVo> byDate = new HashMap<>();
        List<ReportCompletedItemVo> completedItems = new ArrayList<>();
        for (ProgressTracker r : rows) {
            int status = r.getStatus() == null ? -1 : r.getStatus();
            if (status == ProgressTrackerStatus.COMPLETED.getCode()) {
                completed++;
                completedItems.add(ReportCompletedItemVo.builder()
                        .questionId(r.getQuestionId())
                        .summaryContent(r.getSummaryContent())
                        .build());
            } else if (status == ProgressTrackerStatus.IN_PROGRESS.getCode()) {
                inProgress++;
            } else if (status == ProgressTrackerStatus.EXPIRED.getCode()) {
                expired++;
            }
            ProgressCalendarVo vo = byDate.computeIfAbsent(r.getBeginTime(),
                    d -> new ProgressCalendarVo(d, 0L, 0L));
            vo.setTotal(vo.getTotal() + 1);
            if (status == ProgressTrackerStatus.COMPLETED.getCode()) {
                vo.setCompleted(vo.getCompleted() + 1);
            }
        }
        List<ProgressCalendarVo> daily = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            daily.add(byDate.getOrDefault(d, new ProgressCalendarVo(d, 0L, 0L)));
        }
        List<Long> doneQuestionIds = completedItems.stream()
                .map(ReportCompletedItemVo::getQuestionId).distinct().toList();
        Map<Long, String> titles = fetchTitles(doneQuestionIds);
        for (ReportCompletedItemVo item : completedItems) {
            item.setTitle(titles.get(item.getQuestionId()));
        }
        return WeeklyReportVo.builder()
                .startDate(start)
                .endDate(end)
                .totalPlanned((long) rows.size())
                .completedCount(completed)
                .inProgressCount(inProgress)
                .expiredCount(expired)
                .daily(daily)
                .completedItems(completedItems)
                .build();
    }

    /** 计划列表 → 瘦身 VO，并批量回填题目标题/难度（一次 Feign 调用，失败降级为空标题）。 */
    private List<ProgressTrackerBriefVo> toBriefVos(List<ProgressTracker> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> questionIds = list.stream().map(ProgressTracker::getQuestionId).distinct().toList();
        Map<Long, QuestionBriefDto> briefMap = new HashMap<>();
        Result<List<QuestionBriefDto>> briefs = questionFeignClient.getFavoritesBrief(questionIds);
        if (briefs != null && briefs.getCode() != null && briefs.getCode().equals(200) && briefs.getData() != null) {
            for (QuestionBriefDto brief : briefs.getData()) {
                briefMap.put(brief.getQuestionId(), brief);
            }
        }
        List<ProgressTrackerBriefVo> vos = new ArrayList<>();
        for (ProgressTracker plan : list) {
            QuestionBriefDto brief = briefMap.get(plan.getQuestionId());
            vos.add(ProgressTrackerBriefVo.builder()
                    .progressId(plan.getProgressId())
                    .questionId(plan.getQuestionId())
                    .title(brief == null ? null : brief.getTitle())
                    .difficulty(brief == null ? null : brief.getDifficulty())
                    .beginTime(plan.getBeginTime())
                    .status(plan.getStatus())
                    .notesContent(truncate(plan.getNotesContent(), BRIEF_TEXT_LIMIT))
                    .summaryContent(truncate(plan.getSummaryContent(), BRIEF_TEXT_LIMIT))
                    .build());
        }
        return vos;
    }

    /** 批量回填题目 ID → 标题（失败/缺失时该 ID 无标题）。 */
    private Map<Long, String> fetchTitles(List<Long> questionIds) {
        Map<Long, String> titles = new HashMap<>();
        if (questionIds.isEmpty()) {
            return titles;
        }
        Result<List<QuestionBriefDto>> briefs = questionFeignClient.getFavoritesBrief(questionIds);
        if (briefs != null && briefs.getCode() != null && briefs.getCode().equals(200) && briefs.getData() != null) {
            for (QuestionBriefDto brief : briefs.getData()) {
                titles.put(brief.getQuestionId(), brief.getTitle());
            }
        }
        return titles;
    }

    private String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) + "…" : value;
    }

    // ==================== 内部方法 ====================

    /** 创建时的初始状态：当天即进行中，已过期即过期，未来未开始。 */
    private int resolveInitialStatus(LocalDate beginTime) {
        LocalDate today = LocalDate.now();
        if (beginTime.isEqual(today)) {
            return ProgressTrackerStatus.IN_PROGRESS.getCode();
        }
        if (beginTime.isBefore(today)) {
            return ProgressTrackerStatus.EXPIRED.getCode();
        }
        return ProgressTrackerStatus.NOT_STARTED.getCode();
    }

    /**
     * 计划状态懒刷新：查询到了才判断，无常驻定时任务。
     * 开始时间为当天且仍未开始 → 进行中；开始时间已过且未完成 → 已过期；
     * 顺手落库，仅更新变化行。
     */
    private void lazyRefreshStatus(List<ProgressTracker> trackers) {
        LocalDate today = LocalDate.now();
        for (ProgressTracker tracker : trackers) {
            if (tracker.getBeginTime() == null || tracker.getStatus() == null
                    || tracker.getStatus() == ProgressTrackerStatus.COMPLETED.getCode()) {
                continue;
            }
            int target;
            if (tracker.getBeginTime().isEqual(today)) {
                target = ProgressTrackerStatus.IN_PROGRESS.getCode();
            } else if (tracker.getBeginTime().isBefore(today)) {
                target = ProgressTrackerStatus.EXPIRED.getCode();
            } else {
                continue;
            }
            if (tracker.getStatus() != target) {
                tracker.setStatus(target);
                ProgressTracker patch = new ProgressTracker();
                patch.setProgressId(tracker.getProgressId());
                patch.setStatus(target);
                patch.setUpdateTime(LocalDateTime.now());
                progressTrackerMapper.updateById(patch);
            }
        }
    }

    /** 校验计划存在且属于当前用户，防止越权操作他人计划。 */
    private ProgressTracker getOwnProgressOrThrow(Long progressId) {
        ProgressTracker existing = progressTrackerMapper.selectById(progressId);
        if (existing == null) {
            throw new IllegalArgumentException("进度计划不存在");
        }
        if (!existing.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("无权操作该进度计划");
        }
        return existing;
    }

    /**
     * 将区间内聚合结果补齐为零值条目，保证日历视图日期连续。
     * 区间长度由调用方（天/周/月）决定，不固定返回多少天。
     */
    private List<ProgressCalendarVo> fillCalendar(LocalDate start, LocalDate end, List<ProgressCalendarVo> aggregated) {
        Map<LocalDate, ProgressCalendarVo> byDate = aggregated.stream()
                .collect(Collectors.toMap(ProgressCalendarVo::getDate, Function.identity(), (a, b) -> a));
        List<ProgressCalendarVo> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ProgressCalendarVo vo = byDate.get(date);
            result.add(vo != null ? vo : new ProgressCalendarVo(date, 0L, 0L));
        }
        return result;
    }

}
