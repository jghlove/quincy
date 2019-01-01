package com.protocol7.nettyquic.protocol;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.protocol7.nettyquic.utils.Bytes;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.Objects;

public class PacketNumber implements Comparable<PacketNumber> {

  public static PacketNumber parseVarint(final ByteBuf bb) {
    int first = (bb.readByte() & 0xFF);
    int size = ((first & 0b11000000) & 0xFF);
    int rest = ((first & 0b00111111) & 0xFF);

    int len;
    if (size == 0b10000000) {
      len = 1;
    } else if (size == 0b11000000) {
      len = 3;
    } else if (size == 0b0000000) {
      len = 0;
    } else {
      throw new RuntimeException("Unknown size marker");
    }

    byte[] b = new byte[len];
    bb.readBytes(b);
    byte[] pad = new byte[3 - len];
    byte[] bs = Bytes.concat(pad, new byte[] {(byte) rest}, b);

    return new PacketNumber(Ints.fromByteArray(bs));
  }

  public static final PacketNumber MIN = new PacketNumber(0);

  private final long number;

  public PacketNumber(final long number) {
    Preconditions.checkArgument(number >= 0);
    Preconditions.checkArgument(number <= Varint.MAX);

    this.number = number;
  }

  public PacketNumber next() {
    return new PacketNumber(number + 1);
  }

  public PacketNumber max(PacketNumber other) {
    if (this.compareTo(other) > 0) {
      return this;
    } else {
      return other;
    }
  }

  public long asLong() {
    return number;
  }

  public byte[] write() {
    int value = (int) number;
    int from;
    int mask;
    if (value > 16383) {
      from = 0;
      mask = 0b11000000;
    } else if (value > 63) {
      from = 2;
      mask = 0b10000000;
    } else {
      from = 3;
      mask = 0b00000000;
    }
    byte[] bs = Ints.toByteArray(value);
    byte[] b = Arrays.copyOfRange(bs, from, 4);

    b[0] = (byte) (b[0] | mask);

    return b;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PacketNumber that = (PacketNumber) o;
    return number == that.number;
  }

  @Override
  public int hashCode() {
    return Objects.hash(number);
  }

  @Override
  public int compareTo(final PacketNumber o) {
    return Long.compare(this.number, o.number);
  }

  @Override
  public String toString() {
    return Long.toString(number);
  }
}