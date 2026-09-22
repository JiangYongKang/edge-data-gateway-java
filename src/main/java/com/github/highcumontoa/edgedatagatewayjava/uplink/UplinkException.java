package com.github.highcumontoa.edgedatagatewayjava.uplink;

/** 上行投递失败（断网/通道异常）。 */
public class UplinkException extends Exception {
    public UplinkException(String message) { super(message); }

    public UplinkException(String message, Throwable cause) { super(message, cause); }
}
