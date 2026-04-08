package org.wlow.iqabackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import org.wlow.iqabackend.service.MailService;

@SpringBootTest
public class CommonTest {
    @Autowired
    private MailService mailService;

    @Test
    void test() {
        // 测试python服务的/health端点是否可达
        // 用RestClient直接调用http://localhost:8000/health
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .build();
        String response = restClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);
        System.out.println("检测python模型服务: " + response);
    }

    @Test
    void sendPlainTextMail() {
        String to = "gavin1024@qq.com";
        String subject = "测试标题";
        String content = "这是一封测试邮件";

        mailService.sendPlainTextMail(to.trim(), subject, content);
        System.out.println("测试邮件已发送至: " + to.trim());
    }
}
