package org.example.serviceai.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.dto.AiTaskChangesDto;
import org.example.serviceai.dto.AiTaskDto;
import org.example.serviceai.dto.AiTaskRootDto;
import org.example.serviceai.vo.AiTaskVo;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.until.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiAdviceTaskService {

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private QuestionFeignClient  questionFeignClient;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private AIService aiService;
    private final String AI_TASK_KEY="ai_task_key";
    private final String AI_TASK_CHANGES_KEY="ai_task_changes_key";
    private final String AI_ADVICE_KEY="ai_advice_key";
    private final String AI_ADVICE_QUESTION_KEY="ai_advice_question_key";

    /** 用户打开题目后的前 20 分钟只记录代码变化，不调用 AI。 */
    private static final Duration FIRST_EVALUATION_DELAY = Duration.ofMinutes(20);
    /** 完成一次 AI 静默判断后，至少冷却 15 分钟。 */
    private static final Duration EVALUATION_COOLDOWN = Duration.ofMinutes(15);
    /** 最近一次输入后安静 45 秒，避免在用户连续输入时推送。 */
    private static final Duration TYPING_IDLE_TIME = Duration.ofSeconds(45);
    /** 最近 2 分钟仍收到请求，才认为用户还停留在题目页面。 */
    private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(2);
    /** Redis 中观察会话、代码变更和建议的有效期。 */
    private static final Duration TASK_TTL = Duration.ofMinutes(50);

    private String getQuestionContent(Long questionId){
        String key=AI_ADVICE_QUESTION_KEY+questionId;
        Object cachedContent = redisTemplate.opsForValue().get(key);
        String content = cachedContent == null ? null : cachedContent.toString();
        if(Objects.isNull(content)){
            RLock lock=redissonClient.getLock(AI_ADVICE_QUESTION_KEY+questionId);
            try{
                boolean b=lock.tryLock();
                if(b){
                    Result<QuestionDto> body=questionFeignClient.getQuestionInfo(questionId);
                    if(body.getCode()!=200){
                        log.error("获取题目失败，id{}",questionId);
                    }
                    if(body.getData().getDescription()==null){
                        log.error("未找到正文描述,id{}",questionId);
                    }
                    redisTemplate.opsForValue().set(key,body.getData().getDescription(),1, TimeUnit.MINUTES);
                    return  body.getData().getDescription();
                }
            }finally {
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return content == null ? "" : content;
    }


    /**
     * 保存本次编辑增量，并判断是否到达“允许 AI 静默观察”的时间点。
     *
     * <p>返回 true 只表示可以调用 AI 分析，不表示一定要展示悬浮球。
     * AI 返回严格的 OK 时，本次观察不会通知用户。</p>
     *
     * <p>前端应在打开题目时立即发送一次初始化请求，让后端保存初始代码和 openedAt。
     * 前 20 分钟后端只收集变化。编辑变化最好在停止输入约 45 秒后批量发送，
     * 并为每条 change 填写实际发生编辑的 createTime。</p>
     */
    public boolean addChanges(AiTaskDto aiTaskDto){
        String rootKey=AI_TASK_KEY+":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId();
        String changesKey=AI_TASK_CHANGES_KEY+":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId();
        LocalDateTime now=LocalDateTime.now();
        AiTaskRootDto root=(AiTaskRootDto)redisTemplate.opsForValue().get(rootKey);

        // 第一次请求只负责初始化观察会话，不会立即触发 AI。
        // createTime 表示用户打开题目的时间，后续压缩代码快照时必须保持不变。
        if(root==null){
            root=new AiTaskRootDto();
            root.setUserId(aiTaskDto.getUserId());
            root.setQuestionId(aiTaskDto.getQuestionId());
            root.setCreateTime(now);
            root.setLastActiveTime(now);
            root.setCode(Objects.toString(aiTaskDto.getCode(), ""));
            redisTemplate.opsForValue().set(rootKey,root,TASK_TTL);
        }

        List<AiTaskChangesDto> changes = aiTaskDto.getChanges() == null
                ? new ArrayList<>()
                : new ArrayList<>(aiTaskDto.getChanges());
        changes.sort(Comparator.comparing(
                AiTaskChangesDto::getVersion,
                Comparator.nullsLast(Integer::compareTo)
        ));

        // change.createTime 应是用户真正编辑代码的时间，而不是请求到达后端的时间。
        // 这样前端在停止输入 45 秒后批量上报时，后端才能判断用户已经停笔。
        LocalDateTime newestChangeTime = root.getLastChangeTime();
        for(AiTaskChangesDto change:changes){
            if (change.getCreateTime() == null) {
                change.setCreateTime(now);
            }
            if (newestChangeTime == null || change.getCreateTime().isAfter(newestChangeTime)) {
                newestChangeTime = change.getCreateTime();
            }
            redisTemplate.opsForList().rightPush(changesKey, change);
        }
        root.setLastChangeTime(newestChangeTime);
        root.setLastActiveTime(now);
        redisTemplate.opsForValue().set(rootKey,root,TASK_TTL);
        timeChange(":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId());

        Long accumulatedChangeCount = redisTemplate.opsForList().size(changesKey);
        if (accumulatedChangeCount == null || accumulatedChangeCount == 0) {
            // 没有代码变化时无法区分用户是在思考还是已经离开，因此不调用 AI。
            return false;
        }

        // 第一次 AI 观察必须等用户打开题目满 20 分钟。
        boolean firstEvaluationDue = root.getLastEvaluatedTime() == null
                && elapsedAtLeast(root.getCreateTime(), now, FIRST_EVALUATION_DELAY);

        // 已经观察过时，只使用 15 分钟冷却，不再重复等待首次 20 分钟保护期。
        boolean cooldownFinished = root.getLastEvaluatedTime() != null
                && elapsedAtLeast(root.getLastEvaluatedTime(), now, EVALUATION_COOLDOWN);

        // 用户仍在连续打字时不触发。需要前端保留编辑发生时的 createTime。
        boolean stoppedTyping = root.getLastChangeTime() != null
                && elapsedAtLeast(root.getLastChangeTime(), now, TYPING_IDLE_TIME);

        // 请求到达即代表一次活跃行为。未来接入独立心跳后，可以由心跳更新时间。
        boolean userStillActive = root.getLastActiveTime() != null
                && Duration.between(root.getLastActiveTime(), now).compareTo(ACTIVE_WINDOW) <= 0;

        // 时间规则只决定 AI 什么时候在后台看一眼；是否展示建议仍由 AI 的 OK 判断决定。
        return (firstEvaluationDue || cooldownFinished)
                && stoppedTyping
                && userStillActive;
    }

    private boolean elapsedAtLeast(LocalDateTime start, LocalDateTime end, Duration duration) {
        return start != null
                && !end.isBefore(start)
                && Duration.between(start, end).compareTo(duration) >= 0;
    }

    private void timeChange(String key){
        String rootKey=AI_TASK_KEY+":"+key;
        String changesKey=AI_TASK_CHANGES_KEY+":"+key;
        String aiAdviceKey=AI_ADVICE_KEY+":"+key;
        redisTemplate.expire(rootKey,TASK_TTL);
        redisTemplate.expire(changesKey,TASK_TTL);
        redisTemplate.expire(aiAdviceKey,TASK_TTL);

    }
    public void aiTask(AiTaskDto aiTaskDto){
        aiTaskDto.setUserId(UserContext.getUserId());
        String rootKey =AI_TASK_KEY+":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId();
        String changesKey=AI_TASK_CHANGES_KEY+":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId();
        String aiAdviceKey=AI_ADVICE_KEY+":"+aiTaskDto.getUserId()+":"+aiTaskDto.getQuestionId();
        boolean is=addChanges(aiTaskDto);

        if(is){
            AiTaskRootDto root = (AiTaskRootDto) redisTemplate.opsForValue().get(rootKey);
            if (root == null) {
                return;
            }
            Long size=redisTemplate.opsForList().size(changesKey);
            if(size==null){
                return ;
            }
            List<Object> changesObject= redisTemplate.opsForList().range(changesKey, 0L, size);
            if(changesObject==null){
                return ;
            }
            List<AiTaskChangesDto> changes=new ArrayList<>();
            for(Object change:changesObject){
                changes.add((AiTaskChangesDto) change);
            }
            String prompt=buildPrompt(root,changes);

            String advice=aiService.callAi(prompt);
            compactTaskRoot(rootKey, changesKey, root, changes);
            if(advice == null || advice.trim().equalsIgnoreCase("OK")){
                return;
            }
            redisTemplate.opsForList().rightPush(aiAdviceKey,advice);

            WebsocketSendDto websocketSendDto=new WebsocketSendDto();
            websocketSendDto.setUserId(aiTaskDto.getUserId());
            websocketSendDto.setQueueName(WebSocketQueueName.AI_ADVICE.name());

            AiTaskVo aiTaskVo=new AiTaskVo();
            aiTaskVo.setAdvice(advice);
            aiTaskVo.setType("AITASKADVICE");
            aiTaskVo.setQuestionId(root.getQuestionId());

            websocketSendDto.setResult(aiTaskVo);
            rabbitTemplate.convertAndSend(
                    MqContexts.MESSAGE_EXCHANGE,
                    MqContexts.WEBSOCKET_ROUTING_KEY,
                    websocketSendDto
            );



        }
    }

    /**
     *如果回答为ok则证明用户答题方向正确无需提示
     */
    private String buildPrompt(AiTaskRootDto root,List<AiTaskChangesDto> changes){
        StringBuilder changeContent = new StringBuilder();
        for (int index = 0; index < changes.size(); index++) {
            AiTaskChangesDto change = changes.get(index);
            changeContent.append("变更 ").append(index + 1).append("：\n")
                    .append("- 版本：").append(Objects.toString(change.getVersion(), "未知")).append("\n")
                    .append("- 起始字符偏移：").append(Objects.toString(change.getRangeOffset(), "未知")).append("\n")
                    .append("- 被替换字符长度：").append(Objects.toString(change.getRangeLength(), "未知")).append("\n")
                    .append("- 新增或替换内容：\n```text\n")
                    .append(Objects.toString(change.getText(), ""))
                    .append("\n```\n\n");
        }

        return """
                你是 CodeWise 编程练习平台的实时编码建议助手。

                你的任务不是直接完成题目、重写用户代码或给出标准答案，而是根据用户的初始代码和最近编辑变更，判断用户当前的解题方向是否存在值得立即提醒的问题。

                判断规则：
                1. 如果用户当前方向合理，暂未发现明确的逻辑错误、边界遗漏、复杂度风险或会阻碍继续作答的问题，只能回答：OK
                2. `OK` 必须是完整响应，不得添加标点、解释、Markdown 或其他文字。
                3. 只有发现明确且有价值的问题时才给建议，不要因为代码尚未完成、变量名普通、格式不完美或存在多种可行写法而打扰用户。
                4. 建议应简短、具体、可执行，只指出问题和思考方向，不要提供完整实现，不要输出修改后的完整代码。
                5. 不要泄露标准答案，不要直接给出整段核心算法。优先使用提示性表达，让用户自己继续完成。
                6. 变更使用字符偏移描述。请结合初始代码，按版本顺序理解这些变更；不要把删除中的旧内容当成用户当前代码。
                7. 如果缺少题目描述，只判断代码内部能够确定的问题，不猜测题目要求。

                建议输出格式：
                - 无需提示：严格输出 OK
                - 需要提示：输出 1 到 3 句话的中文建议，不要添加“建议如下”等开场白。

                用户ID：%s
                题目ID：%s

                初始代码：
                ```text
                %s
                ```

                最近的代码变更：
                %s
                题目描述：
                %s
                """.formatted(
                Objects.toString(root.getUserId(), "未知"),
                Objects.toString(root.getQuestionId(), "未知"),
                Objects.toString(root.getCode(), ""),
                changeContent,
                getQuestionContent(root.getQuestionId())
        );
    }

    private void compactTaskRoot(
            String rootKey,
            String changesKey,
            AiTaskRootDto root,
            List<AiTaskChangesDto> changes
    ) {
        // AI 已经消费完本批 changes，将它们应用到完整代码快照。
        // 下一次只需要发送新产生的变化，不会重复携带全部历史修改。
        String currentCode = applyChanges(root.getCode(), changes);
        AiTaskRootDto newRoot = new AiTaskRootDto();
        newRoot.setUserId(root.getUserId());
        newRoot.setQuestionId(root.getQuestionId());
        newRoot.setCode(currentCode);

        // createTime 是用户打开题目的时间，必须保留，否则每次判断后都会重新等待 20 分钟。
        newRoot.setCreateTime(root.getCreateTime());
        newRoot.setLastChangeTime(root.getLastChangeTime());
        newRoot.setLastActiveTime(root.getLastActiveTime());

        // 即使 AI 返回 OK，也算完成了一次静默判断，需要进入 15 分钟冷却。
        newRoot.setLastEvaluatedTime(LocalDateTime.now());
        redisTemplate.opsForValue().set(rootKey, newRoot, TASK_TTL);

        // 已应用到快照的 changes 必须删除，防止后续重复发送和重复应用。
        redisTemplate.delete(changesKey);
    }

    private String applyChanges(String sourceCode, List<AiTaskChangesDto> changes) {
        String currentCode = Objects.toString(sourceCode, "");
        Map<Integer, List<AiTaskChangesDto>> changesByVersion = new TreeMap<>();
        for (AiTaskChangesDto change : changes) {
            int version = Objects.requireNonNullElse(change.getVersion(), Integer.MAX_VALUE);
            changesByVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(change);
        }

        for (List<AiTaskChangesDto> versionChanges : changesByVersion.values()) {
            versionChanges.sort(
                    Comparator.comparingInt(this::rangeOffsetOrInvalid).reversed()
            );
            StringBuilder codeBuilder = new StringBuilder(currentCode);
            for (AiTaskChangesDto change : versionChanges) {
                int start = Objects.requireNonNullElse(change.getRangeOffset(), -1);
                int length = Objects.requireNonNullElse(change.getRangeLength(), -1);
                int end = start + length;
                if (start < 0 || length < 0 || end > codeBuilder.length()) {
                    throw new IllegalArgumentException("代码变更范围无效，version=" + change.getVersion());
                }
                codeBuilder.replace(start, end, Objects.toString(change.getText(), ""));
            }
            currentCode = codeBuilder.toString();
        }
        return currentCode;
    }

    private int rangeOffsetOrInvalid(AiTaskChangesDto change) {
        return Objects.requireNonNullElse(change.getRangeOffset(), -1);
    }

    /**
     * 查询指定用户某题目的 AI 建议列表。
     *
     * <p>Redis 列表中保存的是每轮生成的建议原文（String），
     * 读取时会触发一次时间戳压缩（{@code timeChange}）。</p>
     *
     * @param userId     用户 ID
     * @param questionId 题目 ID
     * @return 建议原文列表，无记录时返回空列表
     */
    public List<String> getAiAdvices(Long userId, Long questionId) {
        String key = AI_ADVICE_KEY + ":" + userId + ":" + questionId;
        Long size = redisTemplate.opsForList().size(key);
        if (size == null) {
            return List.of();
        }
        timeChange(":" + userId + ":" + questionId);
        List<Object> range = redisTemplate.opsForList().range(key, 0L, size);
        List<String> advices = new ArrayList<>(range == null ? 0 : range.size());
        if (range != null) {
            for (Object item : range) {
                if (item instanceof String text) {
                    advices.add(text);
                }
            }
        }
        return advices;
    }
}
