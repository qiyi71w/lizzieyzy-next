package featurecat.lizzie.gui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-defined x64 PE: its entry point executes mov eax, status; ret. No CRT, DLL or GPU
 * required.
 */
final class WindowsStatusProcessFixture {
  private WindowsStatusProcessFixture() {}

  static Path create(Path target, int status) throws IOException {
    ByteBuffer pe = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
    pe.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3c, 0x80);
    pe.position(0x80);
    pe.putInt(0x4550).putShort((short) 0x8664).putShort((short) 1);
    pe.putInt(0).putInt(0).putInt(0).putShort((short) 240).putShort((short) 0x22);
    int optional = 0x98;
    pe.putShort((short) 0x20b).put((byte) 14).put((byte) 0);
    pe.putInt(0x200).putInt(0).putInt(0).putInt(0x1000).putInt(0x1000);
    pe.putLong(0x140000000L).putInt(0x1000).putInt(0x200);
    pe.putShort((short) 6)
        .putShort((short) 0)
        .putShort((short) 0)
        .putShort((short) 0)
        .putShort((short) 6)
        .putShort((short) 0);
    pe.putInt(0).putInt(0x2000).putInt(0x200).putInt(0);
    pe.putShort((short) 3).putShort((short) 0x100);
    pe.putLong(0x100000).putLong(0x1000).putLong(0x100000).putLong(0x1000).putInt(0).putInt(16);
    pe.position(optional + 240);
    pe.put(new byte[] {'.', 't', 'e', 'x', 't', 0, 0, 0});
    pe.putInt(6).putInt(0x1000).putInt(0x200).putInt(0x200);
    pe.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0x60000020);
    pe.position(0x200);
    pe.put((byte) 0xb8).putInt(status).put((byte) 0xc3);
    return Files.write(target, pe.array());
  }
}
