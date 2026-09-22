package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;
import com.github.highcumontoa.edgedatagatewayjava.normalize.NormalizationException;
import com.github.highcumontoa.edgedatagatewayjava.normalize.ReadingNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单个设备的逐条采集与归一化逻辑。
 *
 * <p>本类不含线程模型与持久化（由监督器/编排层负责）。
 * 乱序判定所需的“最近已接受时间戳”由调用方传入（来自已持久化位点），
 * 因此批内乱序与跨重启乱序使用同一基准，结论一致。
 */
@Component
public class DeviceCollectorTask {

    private static final Logger log = LoggerFactory.getLogger(DeviceCollectorTask.class);

    /** 单条读数处理结果。 */
    public sealed interface Outcome {
        RawReading raw();

        /** 归一化通过，可入缓冲。 */
        record Accepted(AcceptedRecord record, RawReading raw) implements Outcome {
        }

        /** 归一化被拒绝，携带可区分原因；该读数位点应推进（永不重试脏值）。 */
        record Rejected(RejectedReading rejected, RawReading raw) implements Outcome {
        }
    }

    /**
     * 归一化一条原始读数。
     *
     * @param lastAcceptedTimestamp 该设备最近已接受读数时间戳（&lt;0 表示尚无）
     */
    public Outcome processOne(Device device, RawReading raw,
                              ReadingNormalizer normalizer, long lastAcceptedTimestamp) {
        try {
            NormalizedRecord nr = normalizer.normalize(
                    raw, device.points(), lastAcceptedTimestamp);
            AcceptedRecord accepted = new AcceptedRecord(nr.deviceId(), nr.pointId(),
                    nr.dataType(), nr.unit(), nr.numeric(), nr.flag(), nr.timestamp());
            log.info("[COLLECT-ACCEPT] device={} raw={} normalized={}",
                    device.deviceId(), raw, nr);
            return new Outcome.Accepted(accepted, raw);
        } catch (NormalizationException e) {
            RejectedReading rr = new RejectedReading(
                    raw, e.reason(), e.getMessage(), System.currentTimeMillis());
            log.warn("[COLLECT-REJECT] device={} reason={} raw={} detail={}",
                    device.deviceId(), e.reason(), raw, e.getMessage());
            return new Outcome.Rejected(rr, raw);
        }
    }
}
