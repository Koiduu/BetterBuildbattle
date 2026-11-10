package net.aspir3.betterbattle.fabric;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * Sits in the network pipeline and copies out block changes the server sends.
 * Runs on the network thread; the changes are handed to the main thread through a queue.
 */
final class PacketWatcher extends ChannelInboundHandlerAdapter {
    record BlockChange(int x, int y, int z, String block) {}

    private static final String NAME = "betterbattle_blocks";
    private final Consumer<BlockChange> out;

    private PacketWatcher(Consumer<BlockChange> out) {
        this.out = out;
    }

    static void install(Connection connection, Consumer<BlockChange> out) {
        Channel channel = connection.channel();
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", NAME, new PacketWatcher(out));
                }
            } catch (Exception e) {
                System.err.println("[Better Battle] couldn't watch block updates: " + e);
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            look(msg);
        } catch (Throwable ignored) {
            // never break the connection over stats
        }
        super.channelRead(ctx, msg);
    }

    private void look(Object msg) {
        if (msg instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> p : bundle.subPackets()) look(p);
        } else if (msg instanceof ClientboundBlockUpdatePacket p) {
            add(p.getPos(), p.getBlockState());
        } else if (msg instanceof ClientboundSectionBlocksUpdatePacket p) {
            p.runUpdates(this::add);
        }
    }

    private void add(BlockPos pos, BlockState state) {
        out.accept(new BlockChange(pos.getX(), pos.getY(), pos.getZ(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
    }
}
