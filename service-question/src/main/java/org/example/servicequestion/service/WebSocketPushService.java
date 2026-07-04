package org.example.servicequestion.service;


import org.example.serviceapi.dto.JudgeResultDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebSocketPushService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 推送判题结果给指定用户（最常用）
     */
    public void pushJudgeResult(Long userId, JudgeResultDto result) {
        System.out.println("📤 推送路径: /user/" + userId + "/queue/judge-result");
        // 推送到用户专属队列
        String destination = "/queue/judge-result-" + userId;
        messagingTemplate.convertAndSend(destination, result);
        System.out.println("📤 推送到: " + destination);
        System.out.println("📤 推送判题结果给用户: " + userId + ", 状态: " + result.getSubmitStatus());
    }
    public void pushJudgeResult(Long userId, List<JudgeReturnRecordDto> result) {

        // 推送到用户专属队列
        String destination = "/queue/debug-judge-result-" + userId;
        messagingTemplate.convertAndSend(destination, result);
        System.out.println("📤 推送到: " + destination);

    }
}