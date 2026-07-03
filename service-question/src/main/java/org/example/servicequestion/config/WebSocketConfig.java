package org.example.servicequestion.config;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        System.err.println("========================================");
        System.err.println("🔴 拦截器实例: " + webSocketAuthInterceptor);
        System.err.println("🔴 拦截器是否为 null: " + (webSocketAuthInterceptor == null));
        System.err.println("========================================");
        registry.addEndpoint("/websocket")
                .setAllowedOriginPatterns("*")
                .setAllowedOrigins("*")
                .addInterceptors(webSocketAuthInterceptor)  ;// 添加拦截器
               // .withSockJS();

        System.out.println("✅ 端点注册完成，拦截器已添加");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}