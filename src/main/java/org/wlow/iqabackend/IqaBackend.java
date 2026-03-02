package org.wlow.iqabackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.wlow.iqabackend.mapper")
public class IqaBackend {
    public static void main(String[] args) {
        SpringApplication.run(IqaBackend.class, args);
    }
}
