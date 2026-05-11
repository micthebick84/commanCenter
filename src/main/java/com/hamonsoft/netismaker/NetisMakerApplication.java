package com.hamonsoft.netismaker;

import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class NetisMakerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetisMakerApplication.class, args);
    }
}
