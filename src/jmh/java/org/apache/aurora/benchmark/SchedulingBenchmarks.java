/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.benchmark;

import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.TypeLiteral;

import org.apache.aurora.benchmark.fakes.FakeDriver;
import org.apache.aurora.benchmark.fakes.FakeRescheduleCalculator;
import org.apache.aurora.benchmark.fakes.FakeStatsProvider;
import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.common.util.Clock;
import org.apache.aurora.common.util.testing.FakeClock;
import org.apache.aurora.common.util.testing.FakeTicker;
import org.apache.aurora.gen.ServerInfo;
import org.apache.aurora.scheduler.TaskIdGenerator;
import org.apache.aurora.scheduler.TierModule;
import org.apache.aurora.scheduler.async.AsyncModule;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.config.CliOptions;
import org.apache.aurora.scheduler.config.CommandLine;
import org.apache.aurora.scheduler.config.types.TimeAmount;
import org.apache.aurora.scheduler.configuration.executor.ExecutorSettings;
import org.apache.aurora.scheduler.events.EventSink;
import org.apache.aurora.scheduler.filter.SchedulingFilter;
import org.apache.aurora.scheduler.filter.SchedulingFilterImpl;
import org.apache.aurora.scheduler.mesos.Driver;
import org.apache.aurora.scheduler.mesos.TestExecutorSettings;
import org.apache.aurora.scheduler.offers.Deferment;
import org.apache.aurora.scheduler.offers.HostOffer;
import org.apache.aurora.scheduler.offers.OfferManager;
import org.apache.aurora.scheduler.offers.OfferManagerImpl;
import org.apache.aurora.scheduler.offers.OfferManagerModule;
import org.apache.aurora.scheduler.offers.OfferOrder;
import org.apache.aurora.scheduler.offers.OfferOrderBuilder;
import org.apache.aurora.scheduler.offers.OfferSetImpl;
import org.apache.aurora.scheduler.offers.OfferSettings;
import org.apache.aurora.scheduler.preemptor.BiCache;
import org.apache.aurora.scheduler.preemptor.PreemptorModule;
import org.apache.aurora.scheduler.scheduling.RescheduleCalculator;
import org.apache.aurora.scheduler.scheduling.TaskScheduler;
import org.apache.aurora.scheduler.scheduling.TaskSchedulerImpl;
import org.apache.aurora.scheduler.scheduling.TaskSchedulerImpl.ReservationDuration;
import org.apache.aurora.scheduler.state.ClusterStateImpl;
import org.apache.aurora.scheduler.state.StateModule;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.MutateWork.NoResult;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.IServerInfo;
import org.apache.aurora.scheduler.storage.mem.MemStorageModule;
import org.apache.aurora.scheduler.updater.UpdateAgentReserver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Performance benchmarks for the task scheduling loop.
 */
public class SchedulingBenchmarks {

  /**
   * Constructs scheduler objects and populates offers/tasks for the benchmark run.
   */
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(TimeUnit.SECONDS)
  @Warmup(iterations = 1, time = 10, timeUnit = TimeUnit.SECONDS)
  @Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
  @Fork(1)
  @State(Scope.Thread)
  public abstract static class AbstractBase {
    private static final TimeAmount NO_DELAY = new TimeAmount(1L, Time.MILLISECONDS);
    private static final Amount<Long, Time> DELAY_FOREVER = Amount.of(30L, Time.DAYS);
    private static final Integer BATCH_SIZE = 5;
    protected Storage storage;
    private TaskScheduler taskScheduler;
    private OfferManager offerManager;
    private EventBus eventBus;
    private BenchmarkSettings settings;

    /**
     * Runs once to setup up benchmark state.
     */
    @Setup(Level.Trial)
    public void setUpBenchmark() {
      CommandLine.initializeForTest();
      storage = MemStorageModule.newEmptyStorage();
      eventBus = new EventBus();
      final FakeClock clock = new FakeClock();
      clock.setNowMillis(System.currentTimeMillis());

      CliOptions options = new CliOptions();
      options.preemptor.enablePreemptor = true;
      options.preemptor.preemptionDelay = NO_DELAY;
      options.preemptor.preemptionSlotSearchInterval = NO_DELAY;
      options.preemptor.reservationMaxBatchSize = BATCH_SIZE;

      // TODO(maxim): Find a way to DRY it and reuse existing modules instead.
      Injector injector = Guice.createInjector(
          new StateModule(new CliOptions()),
          new PreemptorModule(options),
          new TierModule(TaskTestUtil.TIER_CONFIG),
          new PrivateModule() {
            @Override
            protected void configure() {

              // We use a no-op executor for async work, as this benchmark is focused on the
              // synchronous scheduling operations.
              bind(ScheduledExecutorService.class).annotatedWith(AsyncModule.AsyncExecutor.class)
                  .toInstance(new NoopExecutor());
              bind(Deferment.class).to(Deferment.Noop.class);
              bind(OfferManager.class).to(OfferManagerImpl.class);
              bind(OfferManagerImpl.class).in(Singleton.class);
              bind(OfferSettings.class).toInstance(
                  new OfferSettings(NO_DELAY,
                      new OfferSetImpl(
                          OfferOrderBuilder.create(ImmutableList.of(OfferOrder.RANDOM))),
                      Amount.of(Long.MAX_VALUE, Time.SECONDS),
                      Long.MAX_VALUE,
                      new FakeTicker()));
              bind(BiCache.BiCacheSettings.class).toInstance(
                  new BiCache.BiCacheSettings(DELAY_FOREVER, ""));
              bind(TaskScheduler.class).to(TaskSchedulerImpl.class);
              bind(TaskSchedulerImpl.class).in(Singleton.class);
              expose(TaskScheduler.class);
              expose(OfferManager.class);
            }
          },
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(new TypeLiteral<Amount<Long, Time>>() { })
                  .annotatedWith(ReservationDuration.class)
                  .toInstance(DELAY_FOREVER);
              bind(TaskIdGenerator.class).to(TaskIdGenerator.TaskIdGeneratorImpl.class);
              bind(new TypeLiteral<Amount<Long, Time>>() { })
                  .annotatedWith(OfferManagerModule.UnavailabilityThreshold.class)
                  .toInstance(Amount.of(1L, Time.MINUTES));
              bind(UpdateAgentReserver.class).to(UpdateAgentReserver.NullAgentReserver.class);
              bind(UpdateAgentReserver.NullAgentReserver.class).in(Singleton.class);
              bind(SchedulingFilter.class).to(SchedulingFilterImpl.class);
              bind(SchedulingFilterImpl.class).in(Singleton.class);
              bind(ExecutorSettings.class).toInstance(TestExecutorSettings.THERMOS_EXECUTOR);
              bind(Storage.class).toInstance(storage);
              bind(Driver.class).toInstance(new FakeDriver());
              bind(RescheduleCalculator.class).toInstance(new FakeRescheduleCalculator());
              bind(Clock.class).toInstance(clock);
              bind(StatsProvider.class).toInstance(new FakeStatsProvider());
              bind(EventSink.class).toInstance(eventBus::post);
              bind(IServerInfo.class).toInstance(IServerInfo.build(new ServerInfo("jmh", "")));
            }
          }
      );

      taskScheduler = injector.getInstance(TaskScheduler.class);
      offerManager = injector.getInstance(OfferManager.class);
      eventBus.register(injector.getInstance(ClusterStateImpl.class));

      withInjector(injector);

      settings = getSettings();
      saveHostAttributes(settings.getHostAttributes());

      Set<HostOffer> offers = new Offers.Builder().build(settings.getHostAttributes());
      Offers.addOffers(offerManager, offers);
      fillUpCluster(offers.size());

      saveTasks(settings.getTasks());
    }

    protected void withInjector(Injector injector) {
      // No-op by default.  Subclasses may use this to retrieve bindings from the injector for use
      // in their test.
    }

    private Set<IScheduledTask> buildClusterTasks(int numOffers) {
      int numSiblingTasks = (int) Math.round(numOffers * settings.getSiblingClusterUtilization());
      int numVictimTasks = (int) Math.round(numOffers * settings.getVictimClusterUtilization());
      return Sets.union(
        new Tasks.Builder()
          .setTier(TaskTestUtil.PROD_TIER_NAME)
          .build(numSiblingTasks),
        new Tasks.Builder()
          .setRole("victim")
          .setTier(TaskTestUtil.DEV_TIER_NAME)
          .build(numVictimTasks));
    }

    private void fillUpCluster(int numOffers) {
      Set<IScheduledTask> tasksToAssign = buildClusterTasks(numOffers);
      saveTasks(tasksToAssign);
      schedule(tasksToAssign);
    }

    protected void saveTasks(final Set<IScheduledTask> tasks) {
      storage.write(
          (NoResult.Quiet) storeProvider -> storeProvider.getUnsafeTaskStore().saveTasks(tasks));
    }

    private void saveHostAttributes(final Set<IHostAttributes> hostAttributesToSave) {
      storage.write((NoResult.Quiet) storeProvider -> {
        for (IHostAttributes attributes : hostAttributesToSave) {
          storeProvider.getAttributeStore().saveHostAttributes(attributes);
        }
      });
    }

    protected abstract BenchmarkSettings getSettings();

    /**
     * Benchmark entry point.
     *
     * @return A "blackhole" to make sure the result is not optimized out.
     * See {@see http://openjdk.java.net/projects/code-tools/jmh/} for more info.
     */
    @Benchmark
    public Set<String> runBenchmark() {
      return schedule(settings.getTasks());
    }

    protected Set<String> schedule(Set<IScheduledTask> tasks) {
      return storage.write((Storage.MutateWork.Quiet<Set<String>>) store -> {
        Set<String> result = null;

        List<List<IScheduledTask>> partitionedTasks = Lists.newArrayList(
            Iterators.partition(tasks.iterator(), 5));

        for (List<IScheduledTask> partition : partitionedTasks) {
          result = taskScheduler.schedule(
              store,
              org.apache.aurora.scheduler.base.Tasks.ids(partition));
        }
        return result;
      });
    }
  }

  /**
   * Tests the successful scheduling of tasks in an almost empty cluster.
   * The cluster will be filled progressively over benchmark repetitions.
   */
  public static class FillClusterBenchmark extends AbstractBase {
    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setSiblingClusterUtilization(0.01)
          .setVictimClusterUtilization(0.01)
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(200000))
          .setTasks(new Tasks.Builder().build(0))
          .build();
    }

    @Override
    public Set<String> runBenchmark() {
      // In contrast to the other tests in this file we have to create new tasks for each
      // benchmark repetition to make sure they can actually be scheduled.
      Set<IScheduledTask> tasks = new Tasks.Builder().build(10);
      saveTasks(tasks);
      return schedule(tasks);
    }
  }

  /**
   * Tests scheduling performance with a task vetoed due to insufficient CPU.
   */
  public static class InsufficientResourcesSchedulingBenchmark extends AbstractBase {
    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(1000))
          .setTasks(new Tasks.Builder()
              .setTier(TaskTestUtil.PROD_TIER_NAME)
              .setCpu(32)
              .build(1)).build();
    }
  }

  /**
   * Tests scheduling performance with a task vetoed due to value constraint mismatch.
   */
  public static class ValueConstraintMismatchSchedulingBenchmark extends AbstractBase {
    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(1000))
          .setTasks(new Tasks.Builder()
              .setTier(TaskTestUtil.PROD_TIER_NAME)
              .addValueConstraint("host", "denied")
              .build(1)).build();
    }
  }

  /**
   * Tests scheduling performance with a task vetoed due to limit constraint mismatch.
   */
  public static class LimitConstraintMismatchSchedulingBenchmark extends AbstractBase {
    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(1000))
          .setTasks(new Tasks.Builder()
              .setTier(TaskTestUtil.PROD_TIER_NAME)
              .addLimitConstraint("host", 0)
              .build(1)).build();
    }
  }

  /**
   * Tests scheduling performance with a large number of tasks and slaves where the cluster
   * is completely filled up.
   */
  public static class ClusterFullUtilizationBenchmark extends AbstractBase {
    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setSiblingClusterUtilization(0.1)
          .setVictimClusterUtilization(0.9)
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(10000))
          .setTasks(new Tasks.Builder()
              .setTier(TaskTestUtil.PROD_TIER_NAME)
              .addLimitConstraint("host", 0)
              .build(1)).build();
    }
  }

  /**
   * Tests preemptor searching for a preemption slot in a completely filled up cluster.
   */
  public static class PreemptorSlotSearchBenchmark extends AbstractBase {
    @Param({"1", "10", "100", "1000"})
    public int numPendingTasks;

    private Runnable pendingTaskProcessor;

    @Override
    protected void withInjector(Injector injector) {
      pendingTaskProcessor =
          injector.getInstance(Key.get(Runnable.class, PreemptorModule.PreemptionSlotFinder.class));
    }

    @Override
    protected BenchmarkSettings getSettings() {
      return new BenchmarkSettings.Builder()
          .setSiblingClusterUtilization(0.1)
          .setVictimClusterUtilization(0.9)
          .setHostAttributes(new Hosts.Builder().setNumHostsPerRack(2).build(10000))
          .setTasks(new Tasks.Builder()
              .setTier(TaskTestUtil.PROD_TIER_NAME)
              .addValueConstraint("host", "denied")
              .build(numPendingTasks)).build();
    }

    @Override
    public Set<String> runBenchmark() {
      pendingTaskProcessor.run();
      // Return non-guessable result to satisfy "blackhole" requirement.
      return ImmutableSet.of("" + System.currentTimeMillis());
    }
  }

  private static class NoopExecutor extends AbstractExecutorService
      implements ScheduledExecutorService {

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return null;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
      return null;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return false;
    }

    @Override
    public void execute(Runnable command) {
    }
  }
}
