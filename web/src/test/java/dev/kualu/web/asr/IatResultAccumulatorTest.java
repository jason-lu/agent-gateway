package dev.kualu.web.asr;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IatResultAccumulatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void accumulatesAppendsAndWpgsReplacements() {
        IatResultAccumulator accumulator = new IatResultAccumulator();

        // 1 — append: first two words of the sentence.
        assertEquals("我是", accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":0,"result":{"sn":1,"ls":false,"pgs":"apd","rg":[1,1],
                 "ws":[{"cw":[{"w":"我"}]},{"cw":[{"w":"是"}]}]}}}"""));

        // 2 — append: the next words join the draft in sn order.
        assertEquals("我是科大", accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":1,"result":{"sn":2,"ls":false,"pgs":"apd","rg":[2,2],
                 "ws":[{"cw":[{"w":"科"}]},{"cw":[{"w":"大"}]}]}}}"""));

        // 3 — wpgs revision: pgs=rpl drops sn 1..2 and this frame's text replaces them.
        assertEquals("我是科大讯飞", accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":1,"result":{"sn":3,"ls":false,"pgs":"rpl","rg":[1,2],
                 "ws":[{"cw":[{"w":"我是科大讯飞"}]}]}}}"""));

        // 4 — last append, and the full text is stable afterwards.
        assertEquals("我是科大讯飞你好", accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":2,"result":{"sn":4,"ls":true,"pgs":"apd","rg":[4,4],
                 "ws":[{"cw":[{"w":"你"}]},{"cw":[{"w":"好"}]}]}}}"""));
        assertEquals("我是科大讯飞你好", accumulator.fullText());
    }

    @Test
    void replacementFrameReusesItsOwnSn() {
        // Realistic wpgs shape where the rpl frame's sn is the start of the replaced span.
        IatResultAccumulator accumulator = new IatResultAccumulator();
        accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":0,"result":{"sn":1,"ls":false,"pgs":"apd","rg":[1,1],
                 "ws":[{"cw":[{"w":"我是"}]}]}}}""");
        accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":1,"result":{"sn":2,"ls":false,"pgs":"apd","rg":[2,2],
                 "ws":[{"cw":[{"w":"科大"}]}]}}}""");
        assertEquals("我是科大讯飞", accept(accumulator, """
                {"code":0,"sid":"s1","data":{"status":1,"result":{"sn":1,"ls":false,"pgs":"rpl","rg":[1,2],
                 "ws":[{"cw":[{"w":"我是科大讯飞"}]}]}}}"""));
    }

    /** Feeds one upstream frame through the parser + accumulator and returns the draft. */
    private String accept(IatResultAccumulator accumulator, String upstreamFrame) {
        return accumulator.accept(IatResultAccumulator.parse(mapper.readTree(upstreamFrame).path("data").path("result")));
    }
}
