package com.github.highcumontoa.edgedatagatewayjava.upstream;

/** 上行通道不可用（断网/拒连）时抛出。 */
public class ChannelException extends RuntimeException {
    public ChannelException(String message) {
        super(message);
    }
}
