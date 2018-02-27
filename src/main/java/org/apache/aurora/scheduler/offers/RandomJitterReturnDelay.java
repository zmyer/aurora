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
package org.apache.aurora.scheduler.offers;

import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;

import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.aurora.common.util.Random;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Calculates the amount of time before an offer should be 'returned' by declining it.
 * Uses a random duration within a fixed window.
 */
@VisibleForTesting
class RandomJitterReturnDelay implements Supplier<Amount<Long, Time>>  {
  private final long minHoldTimeMs;
  private final long maxJitterWindowMs;
  private final Random random;

  RandomJitterReturnDelay(long minHoldTimeMs, long maxJitterWindowMs, Random random) {
    checkArgument(minHoldTimeMs >= 0);
    checkArgument(maxJitterWindowMs >= 0);

    this.minHoldTimeMs = minHoldTimeMs;
    this.maxJitterWindowMs = maxJitterWindowMs;
    this.random = Objects.requireNonNull(random);
  }

  @Override
  public Amount<Long, Time> get() {
    return Amount.of(minHoldTimeMs + random.nextInt((int) maxJitterWindowMs), Time.MILLISECONDS);
  }
}
