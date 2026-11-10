package net.aspir3.betterbattle.forge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.aspir3.betterbattle.legacy.LegacyIds;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;

import java.util.Queue;

/** Copies block changes out of the network pipeline. Runs on the network thread. */
final class PacketWatcher extends ChannelInboundHandlerAdapter {
    static final class BlockChange {
        final int x, y, z;
        final String block;

        BlockChange(int x, int y, int z, String block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }

    private static final String NAME = "betterbattle_blocks";
    private final Queue<BlockChange> out;

    private PacketWatcher(Queue<BlockChange> out) {
        this.out = out;
    }

    static void install(NetworkManager manager, Queue<BlockChange> out) {
        try {
            Channel channel = manager.channel();
            if (channel.pipeline().get(NAME) == null) {
                channel.pipeline().addBefore("packet_handler", NAME, new PacketWatcher(out));
            }
        } catch (Exception e) {
            System.err.println("[Better Battle] couldn't watch block updates: " + e);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof S23PacketBlockChange) {
                S23PacketBlockChange p = (S23PacketBlockChange) msg;
                add(p.getBlockPosition(), p.getBlockState());
            } else if (msg instanceof S22PacketMultiBlockChange) {
                for (S22PacketMultiBlockChange.BlockUpdateData d : ((S22PacketMultiBlockChange) msg).getChangedBlocks()) {
                    add(d.getPos(), d.getBlockState());
                }
            }
        } catch (Throwable ignored) {
            // never break the connection over stats
        }
        super.channelRead(ctx, msg);
    }

    private void add(BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        String id = LegacyIds.modern(Block.blockRegistry.getNameForObject(block).toString(), block.getMetaFromState(state));
        out.add(new BlockChange(pos.getX(), pos.getY(), pos.getZ(), id));
    }
}
