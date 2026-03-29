package eu.inqudium.core.callid;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A highly optimized, thread-safe utility class for generating NanoIDs.
 * <p>
 * This implementation is designed for maximum performance and minimal object
 * allocation. It utilizes {@link java.util.concurrent.ThreadLocalRandom} to
 * avoid the thread contention overhead typically associated with
 * {@link java.security.SecureRandom}. This makes it highly suitable for
 * high-throughput environments where identifiers are needed rapidly, but
 * strict cryptographic security is not required.
 * <p>
 * The generated identifiers are 21 characters long by default and consist
 * solely of URL-safe characters (A-Z, a-z, 0-9, _, -).
 *
 * @see <a href="https://github.com/ai/nanoid">Original NanoID Project</a>
 */
public class FastNanoId {

  // 64 character alphabet (URL-safe)
  private static final char[] ALPHABET =
      "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private static final int DEFAULT_SIZE = 21;

  private FastNanoId() {
    // Prevent instantiation
  }

  public static String randomNanoId() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    char[] id = new char[DEFAULT_SIZE];

    for (int i = 0; i < DEFAULT_SIZE; i++) {
      // Using bitwise AND (& 63) is significantly faster than modulo (%)
      // This works perfectly because our alphabet size is exactly 64 (2^6)
      id[i] = ALPHABET[random.nextInt() & 63];
    }

    return new String(id);
  }
}