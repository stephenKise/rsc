package com.jamierf.rsc.server.net.codec.packet;

import com.jamierf.rsc.server.net.Session;
import com.jamierf.rsc.server.net.codec.field.FieldCodec;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

public class PacketEncoder extends OneToOneEncoder {

    public static final String NAME = "packet-encoder";

    public static ChannelBuffer encodePacket(Class<? extends Packet> type, Packet packet) throws IOException, IllegalAccessException, NoSuchFieldException, BadPaddingException, IllegalBlockSizeException {
        ChannelBuffer payload = ChannelBuffers.dynamicBuffer();

        // For every field attempt to encode it
        for (Field field : type.getDeclaredFields())
            PacketEncoder.getField(field, packet, payload);

        return payload;
    }

    private static void getField(Field field, Packet packet, ChannelBuffer buffer) throws IOException, IllegalAccessException {
        final Class<?> type = field.getType();
        final FieldCodec codec = FieldCodec.getInstance(type);
        if (codec == null)
            throw new IOException("Unsupported field type: " + type);

        final boolean accessible = field.isAccessible();

        field.setAccessible(true);
        final Object value = field.get(packet);
        field.setAccessible(accessible);

        codec.encode(value, buffer);
    }

    private static void writeLength(int length, ChannelBuffer buffer) {
        if (length >= 160) {
            buffer.writeByte(160 + (length / 256));
            buffer.writeByte(length & 0xff);
        }
        else {
            buffer.writeByte(length);
        }
    }

    private final Map<Class<? extends Packet>, Integer> packetTypes;

    public PacketEncoder(Map<Class<? extends Packet>, Integer> packetTypes) {
        this.packetTypes = packetTypes;
    }

    @Override
    protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg) throws Exception {
        final Packet packet = (Packet) msg;
        final Class<? extends Packet> type = packet.getClass();

        // If it's a raw packet then there is no encoding to do
        if (packet instanceof RawPacket)
            return ((RawPacket) packet).buffer;

        if (!packetTypes.containsKey(type))
            throw new IOException("Unrecognised packet type: " + type);

        int id = packetTypes.get(packet.getClass());

        final Session session = (Session) ctx.getAttachment();
        if (session != null)
            id = session.getPacketRotator().rotateOutgoing(id);

        final ChannelBuffer payload = PacketEncoder.encodePacket(type, packet);
        final int length = payload.readableBytes();

        final ChannelBuffer buffer = ChannelBuffers.buffer(length + 3);

        // Write the length header
        PacketEncoder.writeLength(length + 1, buffer);
        if (length >= 160) {
            buffer.writeByte(id); // TODO: unsigned?
            buffer.writeBytes(payload);
        }
        else if (length >= 2) {
            buffer.writeBytes(payload, length - 1, 1);
            buffer.writeByte(id); // TODO: unsigned
            buffer.writeBytes(payload, 0, length - 1);
        }
        else {
            buffer.writeByte(id); // TODO: unsigned
        }

        return buffer;
    }
}