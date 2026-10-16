package com.lm.codeprep;

import org.mybatis.spring.annotation.MapperScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.lm.codeprep.persistence.mapper")
public class CodePrepApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodePrepApplication.class, args);
    }
}
