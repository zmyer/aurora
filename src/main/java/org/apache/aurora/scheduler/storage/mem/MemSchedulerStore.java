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
package org.apache.aurora.scheduler.storage.mem;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.Atomics;

import org.apache.aurora.scheduler.storage.SchedulerStore;

/**
 * An in-memory scheduler store.
 */
class MemSchedulerStore implements SchedulerStore.Mutable {
  private final AtomicReference<String> frameworkId = Atomics.newReference();

  @Override
  public void saveFrameworkId(String newFrameworkId) {
    frameworkId.set(newFrameworkId);
  }

  @Override
  public Optional<String> fetchFrameworkId() {
    return Optional.ofNullable(frameworkId.get());
  }
}
