package org.example.servicereview.service;


import org.example.servicereview.entry.Review;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;



@Service
public class WebSocketPushService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 推送复习结果
     */
    public void pushJudgeResult(Long userId, Review review) {
        System.out.println("📤 推送路径: /user/" + userId + "/queue/review-result");
        // 推送到用户专属队列
        String destination = "/queue/review-result-" + userId;
        messagingTemplate.convertAndSend(destination, review);
        System.out.println("📤 推送到: " + destination);
    }

}