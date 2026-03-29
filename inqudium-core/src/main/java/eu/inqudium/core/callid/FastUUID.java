package eu.inqudium.core.callid;

import java.util.concurrent.ThreadLocalRandom;

public class FastUUID {

  // Pre-calculated lookup table for extremely fast hex conversion
  private static final char[] HEX_DIGITS =
      {'0', '1', '2', '3', '4', '5', '6', '7',
          '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  private FastUUID() {
    // Prevent instantiation
  }

  public static String randomUUIDString() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    long mostSigBits = random.nextLong();
    long leastSigBits = random.nextLong();

    // Set version to 4 (Randomly generated UUID)
    mostSigBits &= ~0x000000000000f000L;
    mostSigBits |= 0x0000000000004000L;

    // Set variant to 2 (Leach-Salz / IETF RFC 4122)
    leastSigBits &= ~0xc000000000000000L;
    leastSigBits |= 0x8000000000000000L;

    // Initialize a character array of exactly 36 characters (the length of a UUID)
    char[] uuidChars = new char[36];

    // Format the most significant bits
    formatHex(mostSigBits >> 32, 8, uuidChars, 0);
    uuidChars[8] = '-';
    formatHex(mostSigBits >> 16, 4, uuidChars, 9);
    uuidChars[13] = '-';
    formatHex(mostSigBits, 4, uuidChars, 14);
    uuidChars[18] = '-';

    // Format the least significant bits
    formatHex(leastSigBits >> 48, 4, uuidChars, 19);
    uuidChars[23] = '-';
    formatHex(leastSigBits, 12, uuidChars, 24);

    // Create the final String using a single allocation
    return new String(uuidChars);
  }

  /**
   * Efficiently converts bits of a long into hexadecimal characters.
   */
  private static void formatHex(long value, int hexCharacters, char[] buffer, int offset) {
    for (int i = 0; i < hexCharacters; i++) {
      // Calculate the right-shift distance (4 bits per hex character)
      int shift = (hexCharacters - 1 - i) * 4;
      // Shift the bits, mask out everything but the lowest 4 bits, and look up the char
      buffer[offset + i] = HEX_DIGITS[(int) ((value >>> shift) & 0x0F)];
    }
  }
}