/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.mysqlclient.impl.util;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public class BufferUtils {
  private static final byte TERMINAL = 0x00;

  public static String readNullTerminatedString(ByteBuf buffer, Charset charset) {
    int len = buffer.bytesBefore(TERMINAL);
    String s = buffer.readCharSequence(len, charset).toString();
    buffer.readByte();
    return s;
  }

  public static String readFixedLengthString(ByteBuf buffer, int length, Charset charset) {
    return buffer.readCharSequence(length, charset).toString();
  }

  public static void writeNullTerminatedString(ByteBuf buffer, CharSequence charSequence, Charset charset) {
    buffer.writeCharSequence(charSequence, charset);
    buffer.writeByte(0);
  }

  public static void writeLengthEncodedInteger(ByteBuf buffer, long value) {
    if (value < 251) {
      // 1-byte integer
      buffer.writeByte((byte) value);
    } else if (value <= 0xFFFF) {
      // 0xFC + 2-byte integer
      buffer.writeByte(0xFC);
      buffer.writeShortLE((int) value);
    } else if (value < 0xFFFFFF) {
      // 0xFD + 3-byte integer
      buffer.writeByte(0xFD);
      buffer.writeMediumLE((int) value);
    } else {
      // 0xFE + 8-byte integer
      buffer.writeByte(0xFE);
      buffer.writeLongLE(value);
    }
  }

  public static long readLengthEncodedInteger(ByteBuf buffer) {
    short firstByte = buffer.readUnsignedByte();
    switch (firstByte) {
      case 0xFB:
        return -1;
      case 0xFC:
        return buffer.readUnsignedShortLE();
      case 0xFD:
        return buffer.readUnsignedMediumLE();
      case 0xFE:
        return buffer.readLongLE();
      default:
        return firstByte;
    }
  }

  public static long countBytesOfLengthEncodedString(ByteBuf buffer, int index) {
    short firstByte = buffer.getUnsignedByte(index);
    switch (firstByte) {
      case 0xFB:
        return 1;
      case 0xFC:
        return 3 + buffer.getUnsignedShortLE(index + 1);
      case 0xFD:
        return 4 + buffer.getUnsignedMediumLE(index + 1);
      case 0xFE:
        return 9 + buffer.getLongLE(index + 1);
      default:
        return 1 + firstByte;
    }
  }

  public static long countBytesOfLengthEncodedInteger(ByteBuf buffer, int index) {
    short firstByte = buffer.getUnsignedByte(index);
    switch (firstByte) {
      case 0xFC:
        return 3;
      case 0xFD:
        return 4;
      case 0xFE:
        return 9;
      default:
        return 1;
    }
  }

  public static void writeLengthEncodedString(ByteBuf buffer, String value, Charset charset) {
    byte[] bytes = value.getBytes(charset);
    writeLengthEncodedInteger(buffer, bytes.length);
    buffer.writeBytes(bytes);
  }

  public static String readLengthEncodedString(ByteBuf buffer, Charset charset) {
    long length = readLengthEncodedInteger(buffer);
    return readFixedLengthString(buffer, (int) length, charset);
  }
}
