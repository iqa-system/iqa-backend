package org.wlow.iqabackend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.wlow.iqabackend.service.MailService;

@SpringBootTest
public class CommonTest {
    @Autowired
    private MailService mailService;

    @Test
    void test() {
        // 这是一个环境依赖测试: 只有本地模型服务启动时才执行健康检查.
        // 如果localhost:8000当前不可达, 则直接跳过, 避免让整套单元测试依赖外部进程常驻.
        try {
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://localhost:8000")
                    .build();
            String response = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
            System.out.println("检测python模型服务: " + response);
        } catch (RestClientException ex) {
            Assumptions.assumeTrue(false, "本地Python模型服务未启动, 跳过健康检查测试");
        }
    }

    @Test
    void sendPlainTextMail() {
        // 邮件发送同样属于环境依赖测试, 默认通过JVM参数或环境变量提供动态测试数据.
        // 这样既能保留"真实发信验证"的能力, 又不会在每次mvn test时都向固定邮箱发送测试邮件.
        String to = firstNonBlank(System.getProperty("test.mail.to"), System.getenv("TEST_MAIL_TO"));
        String subject = firstNonBlank(System.getProperty("test.mail.subject"), System.getenv("TEST_MAIL_SUBJECT"));
        String content = firstNonBlank(System.getProperty("test.mail.content"), System.getenv("TEST_MAIL_CONTENT"));

        Assumptions.assumeTrue(
                hasText(to) && hasText(subject) && hasText(content),
                "未提供测试邮件参数, 跳过发送邮件测试"
        );

        mailService.sendPlainTextMail(to.trim(), subject, content);
        System.out.println("测试邮件已发送至: " + to.trim());
    }

    private String firstNonBlank(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
