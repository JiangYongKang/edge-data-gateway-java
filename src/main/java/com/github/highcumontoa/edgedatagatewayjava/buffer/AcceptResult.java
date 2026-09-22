package com.github.highcumontoa.edgedatagatewayjava.buffer;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.List;

/** append 的确定性结果。 */
public record AcceptResult(
        boolean accepted,
        long seq,
        List<NormalizedRecord> dropped
) {
    static AcceptResult ok(long seq) {
        return new AcceptResult(true, seq, List.of());
    }

    static AcceptResult droppedOldest(long seq, List<NormalizedRecord> dropped) {
        return new AcceptResult(true, seq, List.copyOf(dropped));
    }

    static AcceptResult rejectedNewest(NormalizedRecord candidate) {
        return new AcceptResult(false, candidate.seq(), List.of(candidate));
    }
}
