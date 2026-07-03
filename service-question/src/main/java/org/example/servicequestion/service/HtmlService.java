package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.script.LuoGuHtml;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private RabbitTemplate rabbitTemplate;
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
                throw new RuntimeException("请勿重复提交同一题");
            }
            int r = questionMapper.insert(question);
            if (r != 1) {
                log.info("题目添加失败{}", question.toString());
                throw new RuntimeException("添加失败");
            }
            if (r != 1) {
                log.info(question.toString());
                throw new RuntimeException("题目添加失败");

            }
            System.out.println(question.toString());
            rabbitTemplate.convertAndSend(
                    MqContexts.Ai_EXCHANGE,
                    MqContexts.Ai_TESTCASE_ROUTING_KEY,
                    ToQuestionMessage(question)
            );
            System.out.println("2222222222222");
            return question;


        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
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
