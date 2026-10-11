package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.trace.BedrockTrace;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

@Sharable
public class BedrockBatchDecoder extends MessageToMessageDecoder<BedrockBatchWrapper> {

    public static final String NAME = "bedrock-batch-decoder";

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        if (msg.getUncompressed() == null) {
            throw new IllegalStateException("Batch packet was not decompressed");
        }

        ByteBuf buffer = msg.getUncompressed().slice();
        int packets = 0;
        while (buffer.isReadable()) {
            int packetLength = VarInts.readUnsignedInt(buffer);
            ByteBuf packetBuf = buffer.readRetainedSlice(packetLength);
            out.add(packetBuf);
            packets++;
        }

        if (BedrockTrace.enabled()) {
            int bytes = msg.getUncompressed().readableBytes();
            // A batch arriving on the game link carries the world (chunks, entities); report the large
            // ones by default so the console shows the stream that a freeze would interrupt.
            if (bytes >= 65536 || BedrockTrace.verbose()) {
                BedrockTrace.log(BedrockTrace.LEVEL_TRACE, "batch",
                        "batch in: " + packets + " packet(s), " + bytes + "B uncompressed"
                                + (msg.getAlgorithm() != null ? ", compression=" + msg.getAlgorithm() : ""));
            }
        }
    }
}
