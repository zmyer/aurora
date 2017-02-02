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
package org.apache.aurora.scheduler.cron.quartz;

import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;

import org.apache.aurora.common.args.Arg;
import org.apache.aurora.common.args.CmdLine;
import org.apache.aurora.common.args.constraints.Positive;
import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.util.BackoffHelper;
import org.apache.aurora.scheduler.cron.CronJobManager;
import org.apache.aurora.scheduler.cron.CronPredictor;
import org.apache.aurora.scheduler.cron.CronScheduler;
import org.apache.aurora.scheduler.cron.quartz.AuroraCronJob.CronBatchWorker;
import org.apache.aurora.scheduler.events.PubsubEventModule;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.simpl.SimpleThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.aurora.scheduler.SchedulerServicesModule.addSchedulerActiveServiceBinding;
import static org.quartz.impl.StdSchedulerFactory.PROP_SCHED_INSTANCE_ID;
import static org.quartz.impl.StdSchedulerFactory.PROP_SCHED_MAKE_SCHEDULER_THREAD_DAEMON;
import static org.quartz.impl.StdSchedulerFactory.PROP_SCHED_NAME;
import static org.quartz.impl.StdSchedulerFactory.PROP_THREAD_POOL_CLASS;
import static org.quartz.impl.StdSchedulerFactory.PROP_THREAD_POOL_PREFIX;

/**
 * Provides a {@link CronJobManager} with a Quartz backend. While Quartz itself supports
 * persistence, the scheduler exposed by this module does not persist any state - it simply
 * creates tasks from a {@link org.apache.aurora.gen.JobConfiguration} template on a cron-style
 * schedule.
 */
public class CronModule extends AbstractModule {
  private static final Logger LOG = LoggerFactory.getLogger(CronModule.class);

  @CmdLine(name = "cron_scheduler_num_threads",
      help = "Number of threads to use for the cron scheduler thread pool.")
  private static final Arg<Integer> NUM_THREADS = Arg.create(10);

  @CmdLine(name = "cron_timezone", help = "TimeZone to use for cron predictions.")
  private static final Arg<String> CRON_TIMEZONE = Arg.create("GMT");

  @CmdLine(name = "cron_start_initial_backoff", help =
      "Initial backoff delay while waiting for a previous cron run to be killed.")
  public static final Arg<Amount<Long, Time>> CRON_START_INITIAL_BACKOFF =
      Arg.create(Amount.of(5L, Time.SECONDS));

  @CmdLine(name = "cron_start_max_backoff", help =
      "Max backoff delay while waiting for a previous cron run to be killed.")
  public static final Arg<Amount<Long, Time>> CRON_START_MAX_BACKOFF =
      Arg.create(Amount.of(1L, Time.MINUTES));

  @Positive
  @CmdLine(name = "cron_scheduling_max_batch_size",
      help = "The maximum number of triggered cron jobs that can be processed in a batch.")
  private static final Arg<Integer> CRON_MAX_BATCH_SIZE = Arg.create(10);

  // Global per-JVM ID number generator for the provided Quartz Scheduler.
  private static final AtomicLong ID_GENERATOR = new AtomicLong();

  @Override
  protected void configure() {
    bind(CronPredictor.class).to(CronPredictorImpl.class);
    bind(CronPredictorImpl.class).in(Singleton.class);

    bind(CronJobManager.class).to(CronJobManagerImpl.class);
    bind(CronJobManagerImpl.class).in(Singleton.class);

    bind(CronScheduler.class).to(CronSchedulerImpl.class);
    bind(CronSchedulerImpl.class).in(Singleton.class);

    bind(AuroraCronJobFactory.class).in(Singleton.class);

    bind(AuroraCronJob.class).in(Singleton.class);
    bind(AuroraCronJob.Config.class).toInstance(new AuroraCronJob.Config(
        new BackoffHelper(CRON_START_INITIAL_BACKOFF.get(), CRON_START_MAX_BACKOFF.get())));

    PubsubEventModule.bindSubscriber(binder(), AuroraCronJob.class);

    bind(CronLifecycle.class).in(Singleton.class);
    addSchedulerActiveServiceBinding(binder()).to(CronLifecycle.class);

    bind(new TypeLiteral<Integer>() { })
        .annotatedWith(AuroraCronJob.CronMaxBatchSize.class)
        .toInstance(CRON_MAX_BATCH_SIZE.get());
    bind(CronBatchWorker.class).in(Singleton.class);
    addSchedulerActiveServiceBinding(binder()).to(CronBatchWorker.class);
  }

  @Provides
  TimeZone provideTimeZone() {
    TimeZone timeZone = TimeZone.getTimeZone(CRON_TIMEZONE.get());
    TimeZone systemTimeZone = TimeZone.getDefault();
    if (!timeZone.equals(systemTimeZone)) {
      LOG.warn("Cron schedules are configured to fire according to timezone "
          + timeZone.getDisplayName()
          + " but system timezone is set to "
          + systemTimeZone.getDisplayName());
    }
    return timeZone;
  }

  @Provides
  @Singleton
  static Scheduler provideScheduler(AuroraCronJobFactory jobFactory) throws SchedulerException {
    // There are several ways to create a quartz Scheduler instance.  This path was chosen as the
    // simplest to create a Scheduler that uses a *daemon* QuartzSchedulerThread instance.
    Properties props = new Properties();
    String name = "aurora-cron-" + ID_GENERATOR.incrementAndGet();
    props.setProperty(PROP_SCHED_NAME, name);
    props.setProperty(PROP_SCHED_INSTANCE_ID, name);
    props.setProperty(PROP_THREAD_POOL_CLASS, SimpleThreadPool.class.getCanonicalName());
    props.setProperty(PROP_THREAD_POOL_PREFIX + ".threadCount", NUM_THREADS.get().toString());
    props.setProperty(PROP_THREAD_POOL_PREFIX + ".makeThreadsDaemons", Boolean.TRUE.toString());

    props.setProperty(PROP_SCHED_MAKE_SCHEDULER_THREAD_DAEMON, Boolean.TRUE.toString());
    Scheduler scheduler = new StdSchedulerFactory(props).getScheduler();
    scheduler.setJobFactory(jobFactory);
    return scheduler;
  }
}
