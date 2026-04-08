package org.wlow.iqabackend;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

public class CommonTest {
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
}
