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

  /** An isolated x64 image whose normal import requires the named DLL and `fixture` export. */
  static Path createImported(Path target, String dependency, boolean library) throws IOException {
    ByteBuffer pe = ByteBuffer.allocate(0xa00).order(ByteOrder.LITTLE_ENDIAN);
    pe.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3c, 0x80);
    pe.position(0x80);
    pe.putInt(0x4550).putShort((short) 0x8664).putShort((short) 1);
    pe.putInt(0).putInt(0).putInt(0).putShort((short) 240)
        .putShort((short) (library ? 0x2022 : 0x22));
    int optional = 0x98;
    pe.putShort((short) 0x20b).put((byte) 14).put((byte) 0);
    pe.putInt(0x800).putInt(0).putInt(0).putInt(0x1000).putInt(0x1000);
    pe.putLong(0x140000000L).putInt(0x1000).putInt(0x200);
    pe.putShort((short) 6).putShort((short) 0).putShort((short) 0).putShort((short) 0)
        .putShort((short) 6).putShort((short) 0);
    pe.putInt(0).putInt(0x2000).putInt(0x200).putInt(0);
    pe.putShort((short) 3).putShort((short) 0x100);
    pe.putLong(0x100000).putLong(0x1000).putLong(0x100000).putLong(0x1000).putInt(0).putInt(16);
    pe.putInt(optional + 112 + 8, 0x1100).putInt(optional + 112 + 12, 40);
    if (library) pe.putInt(optional + 112, 0x1400).putInt(optional + 116, 0x80);
    pe.position(optional + 240);
    pe.put(new byte[] {'.', 't', 'e', 'x', 't', 0, 0, 0});
    pe.putInt(0x800).putInt(0x1000).putInt(0x800).putInt(0x200);
    pe.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0)
        .putInt(0x60000020);
    pe.put(0x200, (byte) 0xb8).putInt(0x201, library ? 1 : 0).put(0x205, (byte) 0xc3);
    pe.putInt(0x300, 0x1200).putInt(0x30c, 0x1300).putInt(0x310, 0x1200);
    pe.putLong(0x400, 0x1340).putLong(0x408, 0);
    pe.position(0x500);
    pe.put(dependency.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 0);
    pe.position(0x540);
    pe.putShort((short) 0).put("fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        .put((byte) 0);
    if (library) {
      pe.putInt(0x60c, 0x1450); // export module name
      pe.putInt(0x610, 1).putInt(0x614, 1).putInt(0x618, 1);
      pe.putInt(0x61c, 0x1460).putInt(0x620, 0x1468).putInt(0x624, 0x1470);
      pe.putInt(0x660, 0x1000).putInt(0x668, 0x1478).putShort(0x670, (short) 0);
      pe.position(0x650);
      pe.put("level1.dll\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      pe.position(0x678);
      pe.put("fixture\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    return Files.write(target, pe.array());
  }
}
