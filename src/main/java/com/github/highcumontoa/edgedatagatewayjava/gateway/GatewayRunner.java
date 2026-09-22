package com.github.highcumontoa.edgedatagatewayjava.gateway;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 应用就绪后初始化并（按配置）启动网关。 */
@Component
@Order(0)
public class GatewayRunner implements ApplicationRunner {

    private final GatewayOrchestrator orchestrator;

    public GatewayRunner(GatewayOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        orchestrator.start();
    }
}
