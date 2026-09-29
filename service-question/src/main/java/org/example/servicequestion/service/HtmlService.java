package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.script.LuoGuHtml;
import org.outboxpro.core.OutboxProPublisher;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class HtmlService {
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OutboxProPublisher outboxPublisher;
    private final String LOCK_LUGO_KEY="lock_lugo_key";


    @Transactional
    public Question Luogu(String file) throws Exception {

        LuoGuHtml luoGuHtml = new LuoGuHtml();

        Question question = luoGuHtml.parseLuoguHtml(file);
        question.setCreateTime(LocalDateTime.now());
        question.setUpdateTime(LocalDateTime.now());
        question.setContentHash(DigestUtils.md5DigestAsHex(question.getDescription().getBytes()));
        question.setAiStatue("pending");
        question.setCreateUserId(UserContext.getUserId());
        String key = LOCK_LUGO_KEY + "--" + question.getTitle() + "--" + file;
        RLock lock = redisson.getLock(key);
        try {
            boolean isLock = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!isLock) {
                throw new BizException(CommonErrorCode.DUPLICATE_RESOURCE);
            }
            int r = questionMapper.insert(question);
            if (r != 1) {
                log.info("题目添加失败{}", question.toString());
                throw new BizException(QuestionErrorCode.QUESTION_SAVE_FAILED);
            }
            // AI 用例生成请求与题目插入同事务走 Outbox（原为事务内裸发，存在「DB 提交但消息丢失」隐患）
            outboxPublisher.publish(EventTypes.AI_TESTCASE_REQUEST, ToQuestionMessage(question));
            return question;


        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 原始异常（解析/SQL/中断）只进日志，前端仅见通用导入失败文案
            log.error("洛谷题目导入失败, file={}", file, e);
            throw new BizException(QuestionErrorCode.QUESTION_IMPORT_FAILED, e);
        }finally {
            if(lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    public static QuestionMessage ToQuestionMessage(Question question) {
        QuestionMessage questionMessage = new QuestionMessage();
        questionMessage.setQuestionId(question.getQuestionId());
        questionMessage.setTitle(question.getTitle());
        questionMessage.setDescription(question.getDescription());
        questionMessage.setInputDesc(question.getInputDesc());
        questionMessage.setOutputDesc(question.getOutputDesc());
        questionMessage.setSampleInput(question.getSampleInput());
        questionMessage.setSampleOutput(question.getSampleOutput());
        questionMessage.setCreateUserId(UserContext.getUserId());
        questionMessage.setHint(question.getHint());
        questionMessage.setTags(question.getTags());
        questionMessage.setTimeLimit(question.getTimeLimit());
        questionMessage.setMemoryLimit(question.getMemoryLimit());
        return questionMessage;


    }

}
