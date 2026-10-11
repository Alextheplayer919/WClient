package org.cloudburstmc.protocol.bedrock.netty.codec.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.RequiredArgsConstructor;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.trace.BedrockTrace;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class BedrockEncryptionDecoder extends MessageToMessageDecoder<BedrockBatchWrapper> {

    public static final String NAME = "bedrock-encryption-decoder";

    private static final boolean VALIDATE = Boolean.getBoolean("cloudburst.validateEncryption");

    private final AtomicLong packetCounter = new AtomicLong();
    private final SecretKey key;
    private final Cipher cipher;

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        long counter = this.packetCounter.get();
        try {
            ByteBuffer inBuffer = msg.getCompressed().nioBuffer();
            ByteBuffer outBuffer = inBuffer.duplicate();

            // Copy-safe so we can use the same buffer.
            this.cipher.update(inBuffer, outBuffer);

            ByteBuf output = msg.getCompressed().readSlice(msg.getCompressed().readableBytes() - 8);

            if (VALIDATE) {
                ByteBuf trailer = msg.getCompressed().readSlice(8);

                byte[] actual = new byte[8];
                trailer.readBytes(actual);

                byte[] expected = BedrockEncryptionEncoder.generateTrailer(output, this.key, this.packetCounter);

                if (!Arrays.equals(expected, actual)) {
                    throw new CorruptedFrameException("Invalid encryption trailer");
                }
            }

            if (BedrockTrace.enabled() && counter == 0L) {
                BedrockTrace.log(BedrockTrace.LEVEL_INFO, "encryption",
                        "inbound link switched to encrypted frames (first decrypted batch " + out.size() + ")");
            }

            msg.setCompressed(output.retain());
            out.add(msg.retain());
        } catch (Exception t) {
            if (BedrockTrace.enabled()) {
                // A decrypt failure drops every packet on this link, which the player sees as a freeze:
                // log it here, because the exception alone only reaches logcat/warn.
                BedrockTrace.log(BedrockTrace.LEVEL_ERROR, "encryption",
                        "failed to decrypt inbound batch #" + counter + " (" + msg.getCompressed().readableBytes()
                                + "B): " + t.getClass().getSimpleName() + ": " + t.getMessage()
                                + " - this batch and everything that needed it is lost");
            }
            throw t;
        }
    }
}
