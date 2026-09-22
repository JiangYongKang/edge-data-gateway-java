package com.github.highcumontoa.edgedatagatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文冒烟测试：验证全部 Bean 可装配、
 * 网关可完成初始化与恢复（使用独立临时数据目录，避免污染工作区）。
 *
 * @author Vincent
 */
@SpringBootTest(properties = {
        "edge.gateway.data-dir=target/test-data/app-context",
        "edge.gateway.auto-start=false",
        "edge.gateway.demo-devices=true"
})
public class ApplicationTests {

    @Test
    public void contentLoad() {
    }
}
