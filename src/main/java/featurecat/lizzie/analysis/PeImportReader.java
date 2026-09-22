package featurecat.lizzie.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded read-only parser for PE32 and PE32+ (x86, x64, ARM64) import and delay import tables.
 */
final class PeImportReader {

  static final int IMAGE_FILE_MACHINE_I386 = 0x014c;
  static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
  static final int IMAGE_FILE_MACHINE_ARM64 = 0xaa64;

  private static final int DOS_SIGNATURE = 0x5A4D; // 'MZ'
  private static final int PE_SIGNATURE = 0x00004550; // "PE\0\0"
  private static final int OPTIONAL_HEADER_MAGIC_PE32 = 0x010B;
  private static final int OPTIONAL_HEADER_MAGIC_PE32_PLUS = 0x020B;

  private static final int MAX_SECTIONS = 96;
  private static final int MAX_IMPORT_DESCRIPTORS = 2048;
  private static final int MAX_DELAY_DESCRIPTORS = 2048;
  private static final int MAX_DLL_NAME_LENGTH = 1024;

  private PeImportReader() {}

  record Image(int machine, List<String> imports, List<String> delayImports, IOException delayError) {
    Image {
      imports = imports == null ? List.of() : List.copyOf(imports);
      delayImports = delayImports == null ? List.of() : List.copyOf(delayImports);
    }
  }

  static final class Budget {
    private final long maxBytes;
    private long bytesRead;

    Budget(long maxBytes) {
      if (maxBytes < 0) {
        throw new IllegalArgumentException("maxBytes must be non-negative: " + maxBytes);
      }
      this.maxBytes = maxBytes;
      this.bytesRead = 0;
    }

    long bytesRead() {
      return bytesRead;
    }

    void recordRead(long bytes) throws IOException {
      checkInterrupted();
      if (bytes <= 0) {
        return;
      }
      if (bytesRead + bytes > maxBytes) {
        throw new IOException(
            "Read budget exceeded byte-limit: max=" + maxBytes + ", needed=" + (bytesRead + bytes));
      }
      bytesRead += bytes;
    }

    void checkInterrupted() throws IOException {
      if (Thread.currentThread().isInterrupted()) {
        throw new IOException("Operation interrupted (timeout)");
      }
    }
  }

  private record Section(
      String name,
      long virtualAddress,
      long virtualSize,
      long rawDataPointer,
      long rawDataSize) {}

  static Image read(Path file, Budget budget) throws IOException {
    Objects.requireNonNull(file, "file must not be null");
    Objects.requireNonNull(budget, "budget must not be null");
    budget.checkInterrupted();

    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long fileSize = channel.size();
      if (fileSize < 64) {
        if (fileSize >= 2) {
          budget.recordRead(2);
          if ((readFully(channel, 0, 2).getShort() & 0xFFFF) == DOS_SIGNATURE)
            throw new IOException("Truncated PE image: too small for DOS header: " + fileSize);
        }
        throw new IOException("File too small for DOS header: " + fileSize + " bytes");
      }

      // 1. DOS Header
      budget.recordRead(64);
      ByteBuffer dosBuf = readFully(channel, 0, 64);
      int dosMagic = dosBuf.getShort(0) & 0xFFFF;
      if (dosMagic != DOS_SIGNATURE) {
        throw new IOException("Invalid DOS signature: 0x" + Integer.toHexString(dosMagic));
      }
      long peOffset = Integer.toUnsignedLong(dosBuf.getInt(0x3C));
      if (peOffset < 64 || peOffset + 24 > fileSize) {
        throw new IOException("Invalid PE header offset: " + peOffset);
      }

      // 2. PE Signature and COFF Header
      budget.recordRead(24);
      ByteBuffer coffBuf = readFully(channel, peOffset, 24);
      int peSig = coffBuf.getInt(0);
      if (peSig != PE_SIGNATURE) {
        throw new IOException("Invalid PE signature: 0x" + Integer.toHexString(peSig));
      }

      int machine = coffBuf.getShort(4) & 0xFFFF;
      int numberOfSections = coffBuf.getShort(6) & 0xFFFF;
      int sizeOfOptionalHeader = coffBuf.getShort(20) & 0xFFFF;

      if (numberOfSections > MAX_SECTIONS) {
        throw new IOException(
            "Invalid number of sections: " + numberOfSections + " (max " + MAX_SECTIONS + ")");
      }

      long optHeaderOffset = peOffset + 24;
      if (optHeaderOffset + sizeOfOptionalHeader > fileSize) {
        throw new IOException("File truncated in optional header");
      }
      if (sizeOfOptionalHeader < 2) {
        throw new IOException("Invalid optional header size: " + sizeOfOptionalHeader);
      }

      // 3. Optional Header
      budget.recordRead(sizeOfOptionalHeader);
      ByteBuffer optBuf = readFully(channel, optHeaderOffset, sizeOfOptionalHeader);
      int magic = optBuf.getShort(0) & 0xFFFF;
      if (magic != OPTIONAL_HEADER_MAGIC_PE32 && magic != OPTIONAL_HEADER_MAGIC_PE32_PLUS) {
        throw new IOException(
            "Unsupported or invalid optional header magic: 0x" + Integer.toHexString(magic));
      }

      boolean isPe32Plus = (magic == OPTIONAL_HEADER_MAGIC_PE32_PLUS);
      long imageBase;
      long numRvaAndSizes;
      int dataDirOffset;

      if (isPe32Plus) {
        if (sizeOfOptionalHeader < 112) {
          throw new IOException("Optional header too small for PE32+: " + sizeOfOptionalHeader);
        }
        imageBase = optBuf.getLong(24);
        numRvaAndSizes = Integer.toUnsignedLong(optBuf.getInt(108));
        dataDirOffset = 112;
      } else {
        if (sizeOfOptionalHeader < 96) {
          throw new IOException("Optional header too small for PE32: " + sizeOfOptionalHeader);
        }
        imageBase = Integer.toUnsignedLong(optBuf.getInt(28));
        numRvaAndSizes = Integer.toUnsignedLong(optBuf.getInt(92));
        dataDirOffset = 96;
      }

      long importRva = 0;
      long importSize = 0;
      if (numRvaAndSizes > 1 && sizeOfOptionalHeader >= dataDirOffset + 16) {
        importRva = Integer.toUnsignedLong(optBuf.getInt(dataDirOffset + 8));
        importSize = Integer.toUnsignedLong(optBuf.getInt(dataDirOffset + 12));
      }

      long delayRva = 0;
      long delaySize = 0;
      if (numRvaAndSizes > 13 && sizeOfOptionalHeader >= dataDirOffset + 14 * 8) {
        delayRva = Integer.toUnsignedLong(optBuf.getInt(dataDirOffset + 13 * 8));
        delaySize = Integer.toUnsignedLong(optBuf.getInt(dataDirOffset + 13 * 8 + 4));
      }

      // 4. Section Headers
      long sectionHeadersOffset = optHeaderOffset + sizeOfOptionalHeader;
      long sectionHeadersSize = numberOfSections * 40L;
      if (sectionHeadersOffset + sectionHeadersSize > fileSize) {
        throw new IOException("File truncated in section headers");
      }
      budget.recordRead(sectionHeadersSize);
      ByteBuffer secBuf = readFully(channel, sectionHeadersOffset, (int) sectionHeadersSize);

      List<Section> sections = new ArrayList<>(numberOfSections);
      for (int i = 0; i < numberOfSections; i++) {
        byte[] nameBytes = new byte[8];
        secBuf.get(nameBytes);
        int nameLen = 0;
        while (nameLen < 8 && nameBytes[nameLen] != 0) {
          nameLen++;
        }
        String secName = new String(nameBytes, 0, nameLen, StandardCharsets.US_ASCII);
        long virtualSize = Integer.toUnsignedLong(secBuf.getInt());
        long virtualAddress = Integer.toUnsignedLong(secBuf.getInt());
        long rawDataSize = Integer.toUnsignedLong(secBuf.getInt());
        long rawDataPointer = Integer.toUnsignedLong(secBuf.getInt());
        secBuf.position(secBuf.position() + 16);

        if (rawDataPointer > fileSize) {
          throw new IOException("Section raw data pointer beyond file size: " + rawDataPointer);
        }
        sections.add(new Section(secName, virtualAddress, virtualSize, rawDataPointer, rawDataSize));
      }

      // 5. Normal Imports
      List<String> imports = readImports(channel, importRva, importSize, sections, fileSize, budget);

      // 6. Delay Imports
      List<String> delayImports = List.of();
      IOException delayError = null;
      try {
        delayImports =
            readDelayImports(channel, delayRva, delaySize, imageBase, sections, fileSize, budget);
      } catch (IOException failure) {
        delayError = failure;
      }

      return new Image(machine, imports, delayImports, delayError);
    } catch (ClosedByInterruptException | InterruptedIOException e) {
      throw new IOException("Read interrupted (timeout)", e);
    }
  }

  private static List<String> readImports(
      FileChannel channel,
      long importRva,
      long importSize,
      List<Section> sections,
      long fileSize,
      Budget budget)
      throws IOException {
    if (importRva == 0) {
      return List.of();
    }
    if (importSize < 20) throw new IOException("Invalid import directory size");

    long descriptorOffset = rvaToFileOffset(importRva, sections, fileSize);
    List<String> imports = new ArrayList<>();
    int descriptorCount = 0;

    while (true) {
      descriptorCount++;
      if ((long) descriptorCount * 20 > importSize)
        throw new IOException("Import directory has no terminating descriptor within its bounds");
      if (descriptorCount > MAX_IMPORT_DESCRIPTORS) {
        throw new IOException("Exceeded maximum import descriptors limit: " + MAX_IMPORT_DESCRIPTORS);
      }
      if (descriptorOffset + 20 > fileSize) {
        throw new IOException("Import descriptor table truncated at offset " + descriptorOffset);
      }
      budget.recordRead(20);
      ByteBuffer descBuf = readFully(channel, descriptorOffset, 20);
      int origFirstThunk = descBuf.getInt(0);
      int timeDateStamp = descBuf.getInt(4);
      int forwarderChain = descBuf.getInt(8);
      int nameRva = descBuf.getInt(12);
      int firstThunk = descBuf.getInt(16);

      if (origFirstThunk == 0
          && timeDateStamp == 0
          && forwarderChain == 0
          && nameRva == 0
          && firstThunk == 0) {
        break;
      }
      if (nameRva == 0) {
        if (firstThunk == 0) {
          break;
        }
        throw new IOException("Malformed import descriptor: zero name RVA");
      }

      long nameFileOffset = rvaToFileOffset(Integer.toUnsignedLong(nameRva), sections, fileSize);
      String dllName = readNullTerminatedString(channel, nameFileOffset, fileSize, budget);
      if (!dllName.isEmpty()) {
        imports.add(dllName);
      }
      descriptorOffset += 20;
    }
    return imports;
  }

  private static List<String> readDelayImports(
      FileChannel channel,
      long delayRva,
      long delaySize,
      long imageBase,
      List<Section> sections,
      long fileSize,
      Budget budget)
      throws IOException {
    if (delayRva == 0) {
      return List.of();
    }
    if (delaySize < 32) throw new IOException("Invalid delay import directory size");

    long descriptorOffset = rvaToFileOffset(delayRva, sections, fileSize);
    List<String> delayImports = new ArrayList<>();
    int descriptorCount = 0;

    while (true) {
      descriptorCount++;
      if ((long) descriptorCount * 32 > delaySize)
        throw new IOException("Delay import directory has no terminating descriptor within its bounds");
      if (descriptorCount > MAX_DELAY_DESCRIPTORS) {
        throw new IOException(
            "Exceeded maximum delay import descriptors limit: " + MAX_DELAY_DESCRIPTORS);
      }
      if (descriptorOffset + 32 > fileSize) {
        throw new IOException("Delay import descriptor table truncated at offset " + descriptorOffset);
      }
      budget.recordRead(32);
      ByteBuffer descBuf = readFully(channel, descriptorOffset, 32);
      int grAttrs = descBuf.getInt(0);
      int szName = descBuf.getInt(4);
      int phmod = descBuf.getInt(8);
      int pIAT = descBuf.getInt(12);
      int pINT = descBuf.getInt(16);
      int pBoundIAT = descBuf.getInt(20);
      int pUnloadIAT = descBuf.getInt(24);
      int dwTimeStamp = descBuf.getInt(28);

      if (grAttrs == 0
          && szName == 0
          && phmod == 0
          && pIAT == 0
          && pINT == 0
          && pBoundIAT == 0
          && pUnloadIAT == 0
          && dwTimeStamp == 0) {
        break;
      }
      if (szName == 0) {
        break;
      }

      long rawNamePtr = Integer.toUnsignedLong(szName);
      long nameRva;
      if ((grAttrs & 1) != 0) {
        // RVA mode
        nameRva = rawNamePtr;
      } else {
        // VA mode
        if (rawNamePtr < imageBase) {
          throw new IOException(
              "Delay import VA mode pointer 0x"
                  + Long.toHexString(rawNamePtr)
                  + " is below ImageBase 0x"
                  + Long.toHexString(imageBase));
        }
        long diff = rawNamePtr - imageBase;
        if (diff > 0xFFFFFFFFL) {
          throw new IOException(
              "Delay import VA mode pointer 0x"
                  + Long.toHexString(rawNamePtr)
                  + " exceeds 32-bit RVA range from ImageBase 0x"
                  + Long.toHexString(imageBase));
        }
        nameRva = diff;
      }

      long nameFileOffset = rvaToFileOffset(nameRva, sections, fileSize);
      String dllName = readNullTerminatedString(channel, nameFileOffset, fileSize, budget);
      if (!dllName.isEmpty()) {
        delayImports.add(dllName);
      }
      descriptorOffset += 32;
    }
    return delayImports;
  }

  private static long rvaToFileOffset(long rva, List<Section> sections, long fileSize)
      throws IOException {
    if (rva == 0) {
      throw new IOException("Invalid RVA: zero");
    }
    for (Section s : sections) {
      long vAddr = s.virtualAddress();
      long vSize = s.virtualSize();
      long rawSize = s.rawDataSize();
      long rawPtr = s.rawDataPointer();

      long span = (vSize > 0) ? vSize : rawSize;
      if (rva >= vAddr && rva < vAddr + span) {
        long offsetIntoSection = rva - vAddr;
        if (offsetIntoSection >= rawSize) {
          throw new IOException(
              "RVA 0x" + Long.toHexString(rva) + " falls in uninitialized section data");
        }
        long fileOffset = rawPtr + offsetIntoSection;
        if (fileOffset >= fileSize) {
          throw new IOException(
              "RVA 0x"
                  + Long.toHexString(rva)
                  + " maps to file offset "
                  + fileOffset
                  + " beyond file size "
                  + fileSize);
        }
        return fileOffset;
      }
    }

    // Check if RVA falls into headers before the first section
    long minSectionVAddr = Long.MAX_VALUE;
    for (Section s : sections) {
      if (s.virtualAddress() < minSectionVAddr) {
        minSectionVAddr = s.virtualAddress();
      }
    }
    if (rva < minSectionVAddr && rva < fileSize) {
      return rva;
    }

    throw new IOException("Invalid RVA 0x" + Long.toHexString(rva) + ": not mapped by any section");
  }

  private static ByteBuffer readFully(FileChannel channel, long position, int length)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    long currentPos = position;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, currentPos);
      if (read == 0) throw new IOException("No progress reading PE image at offset " + currentPos);
      if (read < 0) {
        throw new IOException("Unexpected EOF reading " + length + " bytes at offset " + position);
      }
      currentPos += read;
    }
    buffer.flip();
    return buffer;
  }

  private static String readNullTerminatedString(
      FileChannel channel, long fileOffset, long fileSize, Budget budget) throws IOException {
    if (fileOffset < 0 || fileOffset >= fileSize) {
      throw new IOException("String offset " + fileOffset + " outside file bounds " + fileSize);
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(128);
    long currentOffset = fileOffset;
    int totalRead = 0;

    while (totalRead < MAX_DLL_NAME_LENGTH) {
      long remainingFile = fileSize - currentOffset;
      if (remainingFile <= 0) {
        throw new IOException("Unterminated string at EOF (offset " + currentOffset + ")");
      }
      int toRead =
          (int)
              Math.min(
                  buffer.capacity(),
                  Math.min(remainingFile, (long) (MAX_DLL_NAME_LENGTH - totalRead)));
      buffer.clear();
      buffer.limit(toRead);
      budget.recordRead(toRead);
      int read = channel.read(buffer, currentOffset);
      if (read <= 0) {
        throw new IOException("Unexpected EOF while reading string at offset " + currentOffset);
      }
      buffer.flip();
      boolean foundNull = false;
      while (buffer.hasRemaining()) {
        byte b = buffer.get();
        totalRead++;
        if (b == 0) {
          foundNull = true;
          break;
        }
        baos.write(b);
      }
      if (foundNull) {
        return baos.toString(StandardCharsets.UTF_8);
      }
      currentOffset += read;
    }
    throw new IOException(
        "String exceeds maximum allowed length ("
            + MAX_DLL_NAME_LENGTH
            + " bytes) or is unterminated");
  }
}
