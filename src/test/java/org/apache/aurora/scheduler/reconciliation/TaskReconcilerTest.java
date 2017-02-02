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
package org.apache.aurora.scheduler.reconciliation;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskEvent;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.mesos.Driver;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.apache.aurora.scheduler.testing.FakeScheduledExecutor;
import org.apache.mesos.Protos;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.common.quantity.Time.MINUTES;
import static org.apache.aurora.common.quantity.Time.SECONDS;
import static org.apache.aurora.scheduler.reconciliation.TaskReconciler.EXPLICIT_STAT_NAME;
import static org.apache.aurora.scheduler.reconciliation.TaskReconciler.IMPLICIT_STAT_NAME;
import static org.apache.aurora.scheduler.reconciliation.TaskReconciler.TASK_TO_PROTO;
import static org.apache.aurora.scheduler.reconciliation.TaskReconciler.TaskReconcilerSettings;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;

public class TaskReconcilerTest extends EasyMockTest {
  private static final Amount<Long, Time> INITIAL_DELAY = Amount.of(10L, MINUTES);
  private static final Amount<Long, Time> EXPLICIT_SCHEDULE = Amount.of(60L, MINUTES);
  private static final Amount<Long, Time> IMPLICT_SCHEDULE = Amount.of(180L, MINUTES);
  private static final Amount<Long, Time> SPREAD = Amount.of(30L, MINUTES);
  private static final Amount<Long, Time> BATCH_DELAY = Amount.of(3L, SECONDS);
  private static final int BATCH_SIZE = 1;
  private static final TaskReconcilerSettings SETTINGS = new TaskReconcilerSettings(
      INITIAL_DELAY,
      EXPLICIT_SCHEDULE,
      IMPLICT_SCHEDULE,
      SPREAD,
      BATCH_DELAY,
      BATCH_SIZE);

  private StorageTestUtil storageUtil;
  private StatsProvider statsProvider;
  private Driver driver;
  private ScheduledExecutorService executorService;
  private AtomicLong explicitRuns;
  private AtomicLong implicitRuns;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    statsProvider = createMock(StatsProvider.class);
    driver = createMock(Driver.class);
    executorService = createMock(ScheduledExecutorService.class);
    explicitRuns = new AtomicLong();
    implicitRuns = new AtomicLong();
  }

  @Test
  public void testExecution() {
    expect(statsProvider.makeCounter(EXPLICIT_STAT_NAME)).andReturn(explicitRuns);
    expect(statsProvider.makeCounter(IMPLICIT_STAT_NAME)).andReturn(implicitRuns);
    FakeScheduledExecutor clock =
        FakeScheduledExecutor.scheduleAtFixedRateExecutor(executorService, 2, 5);

    IScheduledTask task1 = makeTask("id1", TaskTestUtil.makeConfig(TaskTestUtil.JOB));
    IScheduledTask task2 = makeTask("id2", TaskTestUtil.makeConfig(TaskTestUtil.JOB));
    storageUtil.expectOperations();
    storageUtil.expectTaskFetch(
        Query.unscoped().byStatus(Tasks.SLAVE_ASSIGNED_STATES),
        task1,
        task2).times(7);

    List<List<Protos.TaskStatus>> batches = Lists.partition(ImmutableList.of(
        TASK_TO_PROTO.apply(task1),
        TASK_TO_PROTO.apply(task2)), BATCH_SIZE);

    driver.reconcileTasks(batches.get(0));
    expectLastCall().times(7);

    driver.reconcileTasks(batches.get(1));
    expectLastCall().times(7);

    driver.reconcileTasks(EasyMock.anyObject());
    expectLastCall().times(3);

    control.replay();

    TaskReconciler reconciler = new TaskReconciler(
        SETTINGS,
        storageUtil.storage,
        driver,
        executorService,
        statsProvider);

    reconciler.startAsync().awaitRunning();

    clock.advance(INITIAL_DELAY);
    assertEquals(1L, explicitRuns.get());
    assertEquals(0L, implicitRuns.get());

    clock.advance(SPREAD);
    assertEquals(1L, explicitRuns.get());
    assertEquals(1L, implicitRuns.get());

    clock.advance(EXPLICIT_SCHEDULE);
    assertEquals(2L, explicitRuns.get());
    assertEquals(1L, implicitRuns.get());

    clock.advance(IMPLICT_SCHEDULE);
    assertEquals(5L, explicitRuns.get());
    assertEquals(2L, implicitRuns.get());

    reconciler.triggerExplicitReconciliation(Optional.of(BATCH_SIZE));
    assertEquals(6L, explicitRuns.get());
    reconciler.triggerImplicitReconciliation();
    assertEquals(3L, implicitRuns.get());

    reconciler.triggerExplicitReconciliation(Optional.absent());
    assertEquals(7L, explicitRuns.get());
    assertEquals(3L, implicitRuns.get());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidImplicitDelay() throws Exception {
    control.replay();

    new TaskReconcilerSettings(
        INITIAL_DELAY,
        EXPLICIT_SCHEDULE,
        IMPLICT_SCHEDULE,
        Amount.of(Long.MAX_VALUE, MINUTES),
        BATCH_DELAY,
        BATCH_SIZE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidExplicitDelay() throws Exception {
    control.replay();

    new TaskReconcilerSettings(
        Amount.of(Long.MAX_VALUE, MINUTES),
        EXPLICIT_SCHEDULE,
        IMPLICT_SCHEDULE,
        SPREAD,
        BATCH_DELAY,
        BATCH_SIZE);
  }

  private static IScheduledTask makeTask(String id, ITaskConfig config) {
    return IScheduledTask.build(new ScheduledTask()
        .setStatus(ScheduleStatus.ASSIGNED)
        .setTaskEvents(ImmutableList.of(
            new TaskEvent(100L, ScheduleStatus.ASSIGNED)
                .setMessage("message")
                .setScheduler("scheduler"),
            new TaskEvent(101L, ScheduleStatus.ASSIGNED)
                .setMessage("message")
                .setScheduler("scheduler2")))
        .setAncestorId("ancestor")
        .setFailureCount(3)
        .setAssignedTask(new AssignedTask()
            .setInstanceId(2)
            .setTaskId(id)
            .setSlaveId("slave-id")
            .setAssignedPorts(ImmutableMap.of("http", 1000))
            .setTask(config.newBuilder())));
  }
}
