package com.protocol7.nettyquick.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.protocol7.nettyquick.Connection;
import com.protocol7.nettyquick.protocol.frames.AckBlock;
import com.protocol7.nettyquick.protocol.frames.AckFrame;

// TODO resends
public class PacketBuffer {

  private final Map<PacketNumber, Packet> buffer = Maps.newConcurrentMap();
  private final BlockingQueue<PacketNumber> ackQueue = Queues.newArrayBlockingQueue(1000);
  private final Connection connection;

  public PacketBuffer(final Connection connection) {
    this.connection = connection;
  }

  @VisibleForTesting
  protected Map<PacketNumber, Packet> getBuffer() {
    return buffer;
  }

  public void send(Packet packet) {
    buffer.put(packet.getPacketNumber(), packet);
    connection.sendPacket(packet);
  }

  public void onPacket(Packet packet) {
    ackQueue.add(packet.getPacketNumber());

    ack(packet);

    if (!acksOnly(packet)) {
      flushAcks();
    }
  }

  private void ack(Packet packet) {
    packet.getPayload().getFrames().stream().filter(frame -> frame instanceof AckFrame).forEach(frame -> ack((AckFrame) frame));
  }

  private void ack(AckFrame frame) {
    frame.getBlocks().forEach(this::ack);
  }

  private void ack(AckBlock block) {
    // TODO optimize
    long smallest = block.getSmallest().asLong();
    long largest = block.getLargest().asLong();
    for (long i = smallest; i<=largest; i++) {
      buffer.remove(new PacketNumber(i));
    }
  }

  private void flushAcks() {
    List<AckBlock> blocks = toBlocks(ackQueue);
    AckFrame ackFrame = new AckFrame(123, blocks);
    Packet packet = new ShortPacket(false,
                                    false,
                                    PacketType.Four_octets,
                                    connection.getConnectionId(),
                                    connection.nextPacketNumber(),
                                    new Payload(ackFrame));

    send(packet);
  }

  // TODO break out and test directly
  private List<AckBlock> toBlocks(BlockingQueue<PacketNumber> queue) {
    List<PacketNumber> pns = Lists.newArrayList();
    ackQueue.drainTo(pns);
    List<Long> pnsLong = pns.stream().map(packetNumber -> packetNumber.asLong()).collect(Collectors.toList());
    Collections.sort(pnsLong);

    List<AckBlock> blocks = Lists.newArrayList();
    long lower = -1;
    long upper = -1;
    for (long pn : pnsLong) {
      if (lower == -1) {
        lower = pn;
        upper = pn;
      } else {
        if (pn > upper + 1) {
          blocks.add(AckBlock.fromLongs(lower, upper));
          lower = pn;
          upper = pn;
        } else {
          upper++;
        }
      }
    }
    blocks.add(AckBlock.fromLongs(lower, upper));
    return blocks;
  }

  private boolean acksOnly(Packet packet) {
    return packet.getPayload().getFrames().stream().allMatch(frame -> frame instanceof AckFrame);
  }

}
