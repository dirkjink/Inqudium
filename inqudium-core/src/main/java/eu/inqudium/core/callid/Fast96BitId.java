package eu.inqudium.core.callid;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A highly optimized, thread-safe utility class for generating 96-bit identifiers.
 * <p>
 * This implementation generates 96 bits of randomness (12 bytes) and formats
 * them as a 24-character hexadecimal string without creating intermediate objects.
 */
public class Fast96BitId {

  // Pre-calculated lookup table for extremely fast hex conversion
  private static final char[] HEX_DIGITS =
      {'0', '1', '2', '3', '4', '5', '6', '7',
          '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  private Fast96BitId() {
    // Prevent instantiation
  }

  /**
   * Generates a 96-bit random identifier formatted as a 24-character hex string.
   *
   * @return A 24-character hexadecimal string.
   */
  public static String randomId() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    // Fetch exactly 96 bits of randomness
    long part1 = random.nextLong(); // 64 bits
    int part2 = random.nextInt();   // 32 bits

    // Initialize a character array of exactly 24 characters (96 bits / 4 bits per hex char)
    char[] idChars = new char[24];

    // Format the 64-bit part into the first 16 characters
    formatHexLong(part1, 16, idChars, 0);

    // Format the 32-bit part into the remaining 8 characters
    formatHexInt(part2, 8, idChars, 16);

    // Create the final String using a single allocation
    return new String(idChars);
  }

  /**
   * Efficiently converts bits of a long into hexadecimal characters.
   */
  private static void formatHexLong(long value, int hexCharacters, char[] buffer, int offset) {
    for (int i = 0; i < hexCharacters; i++) {
      // Calculate the right-shift distance (4 bits per hex character)
      int shift = (hexCharacters - 1 - i) * 4;
      // Shift the bits, mask out everything but the lowest 4 bits, and look up the char
      buffer[offset + i] = HEX_DIGITS[(int) ((value >>> shift) & 0x0F)];
    }
  }

  /**
   * Efficiently converts bits of an int into hexadecimal characters.
   */
  private static void formatHexInt(int value, int hexCharacters, char[] buffer, int offset) {
    for (int i = 0; i < hexCharacters; i++) {
      // Calculate the right-shift distance (4 bits per hex character)
      int shift = (hexCharacters - 1 - i) * 4;
      // Shift the bits, mask out everything but the lowest 4 bits, and look up the char
      buffer[offset + i] = HEX_DIGITS[(value >>> shift) & 0x0F];
    }
  }
}
