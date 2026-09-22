package com.github.highcumontoa.edgedatagatewayjava.config;

import com.github.highcumontoa.edgedatagatewayjava.device.DemoDeviceFactory;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.spool.DeviceCheckpointStore;
import com.github.highcumontoa.edgedatagatewayjava.spool.RejectionLog;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import com.github.highcumontoa.edgedatagatewayjava.upstream.MockUpstreamChannel;
import com.github.highcumontoa.edgedatagatewayjava.upstream.UpstreamChannel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 网关 Bean 装配：存储层与本地模拟上行通道均为确定性的本地实现。 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    @Bean
    public SpoolStore spoolStore() {
        return new SpoolStore();
    }

    @Bean
    public DeviceCheckpointStore deviceCheckpointStore() {
        return new DeviceCheckpointStore();
    }

    @Bean
    public RejectionLog rejectionLog() {
        return new RejectionLog();
    }

    @Bean
    public UpstreamChannel upstreamChannel() {
        return new MockUpstreamChannel();
    }

    /** 内置演示设备；可通过 edge.gateway.demo-devices=false 关闭（返回空列表）。 */
    @Bean
    public List<Device> demoDevices(GatewayProperties props) {
        return props.isDemoDevices() ? DemoDeviceFactory.create() : List.of();
    }
}
