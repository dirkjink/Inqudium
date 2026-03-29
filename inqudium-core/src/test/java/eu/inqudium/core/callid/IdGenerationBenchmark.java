package eu.inqudium.core.callid;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * Microbenchmark to compare the throughput of different ID generation strategies.
 * <p>
 * Measures operations per millisecond to clearly show the performance differences
 * between standard implementations and our optimized zero-allocation generators.
 *
 * <p>
 * <h1>JMH Benchmark Results and Analysis.</h1>
 * <p>
 * The data clearly demonstrates the massive performance benefits of the custom,
 * optimized ID generators compared to the standard Java library. All three custom
 * implementations significantly outperform the built-in {@link java.util.UUID}.
 *
 * <h3>1. Throughput Comparison (Operations per Millisecond)</h3>
 * <p>
 * This metric ({@code thrpt}) shows how many IDs each strategy can generate in a
 * single millisecond. A higher number indicates better performance.
 * <table border="1">
 * <caption>Throughput Results Comparison</caption>
 * <tr>
 * <th>Implementation</th>
 * <th>Score (ops/ms)</th>
 * <th>Relative Performance</th>
 * </tr>
 * <tr>
 * <td>Fast96BitId</td>
 * <td>~ 69,096</td>
 * <td><b>19.3x faster</b></td>
 * </tr>
 * <tr>
 * <td>FastUUID</td>
 * <td>~ 52,656</td>
 * <td><b>14.7x faster</b></td>
 * </tr>
 * <tr>
 * <td>FastNanoId</td>
 * <td>~ 43,760</td>
 * <td><b>12.2x faster</b></td>
 * </tr>
 * <tr>
 * <td>StandardJavaUUID</td>
 * <td>~ 3,571</td>
 * <td>1.0x (Baseline)</td>
 * </tr>
 * </table>
 *
 * <h3>2. Memory Allocation (Bytes per Operation)</h3>
 * <p>
 * The metric {@code gc.alloc.rate.norm} reveals how much memory is allocated on
 * the heap for each generated ID. A lower number means less pressure on the
 * Garbage Collector (GC).
 * <table border="1">
 * <caption>Memory Allocation Comparison</caption>
 * <tr>
 * <th>Implementation</th>
 * <th>Allocation (B/op)</th>
 * <th>Memory Reduction</th>
 * </tr>
 * <tr>
 * <td>Fast96BitId</td>
 * <td>128 Bytes</td>
 * <td><b>- 60.0%</b></td>
 * </tr>
 * <tr>
 * <td>FastNanoId</td>
 * <td>128 Bytes</td>
 * <td><b>- 60.0%</b></td>
 * </tr>
 * <tr>
 * <td>FastUUID</td>
 * <td>168 Bytes</td>
 * <td><b>- 47.5%</b></td>
 * </tr>
 * <tr>
 * <td>StandardJavaUUID</td>
 * <td>320 Bytes</td>
 * <td>0% (Baseline)</td>
 * </tr>
 * </table>
 *
 * <h3>Key Takeaways &amp; Technical Insights</h3>
 * <ul>
 * <li><b>The Bottleneck of the Standard UUID:</b> StandardJavaUUID allocates 320 bytes per call,
 * triggering the Garbage Collector much more frequently. Furthermore, its internal reliance
 * on {@code SecureRandom} causes massive overhead compared to {@code ThreadLocalRandom}.</li>
 * <li><b>The Zero-Allocation Strategy Works:</b> Fast96BitId and FastNanoId only allocate 128 bytes
 * per operation. This represents the absolute minimum memory required to allocate the final
 * {@link java.lang.String} object and its backing array in Java. No temporary objects are wasted.</li>
 * <li><b>Fast96BitId is the Absolute Winner:</b> By requiring less entropy (only 96 bits) and
 * assembling a shorter string (24 characters), it achieves the highest throughput,
 * approaching 70,000 IDs per millisecond.</li>
 * <li><b>FastUUID beats FastNanoId:</b> While generating a 36-character UUID seems like it should
 * be slower than a 21-character NanoId, FastUUID uses highly predictable, sequential bit-shifting
 * and hardcoded array indices. The CPU and the Java JIT compiler optimize this much better
 * than the {@code for}-loop and array lookups required by FastNanoId.</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IdGenerationBenchmark {

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder().addProfiler("gc").include(IdGenerationBenchmark.class.getSimpleName()).build();
    new Runner(opt).run();
  }

  @Benchmark
  public void benchmarkFast96BitId(Blackhole blackhole) {
    // Consume the result using Blackhole to prevent the JIT compiler
    // from optimizing away the method call entirely (dead code elimination)
    blackhole.consume(Fast96BitId.randomId());
  }

  @Benchmark
  public void benchmarkFastNanoId(Blackhole blackhole) {
    // Consume the generated NanoID
    blackhole.consume(FastNanoId.randomNanoId());
  }

  @Benchmark
  public void benchmarkFastUUID(Blackhole blackhole) {
    // Consume the generated FastUUID string
    blackhole.consume(FastUUID.randomUUIDString());
  }

  @Benchmark
  public void benchmarkStandardJavaUUID(Blackhole blackhole) {
    // Baseline measurement using the standard java.util.UUID for comparison
    blackhole.consume(UUID.randomUUID().toString());
  }
}