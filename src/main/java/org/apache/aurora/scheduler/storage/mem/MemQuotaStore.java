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

import java.util.Map;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.scheduler.storage.QuotaStore;
import org.apache.aurora.scheduler.storage.entities.IResourceAggregate;

/**
 * An in-memory quota store.
 */
class MemQuotaStore implements QuotaStore.Mutable {
  @VisibleForTesting
  static final String QUOTA_STORE_SIZE = "mem_storage_quota_size";

  private final Map<String, IResourceAggregate> quotas = Maps.newConcurrentMap();

  @Inject
  MemQuotaStore(StatsProvider statsProvider) {
    statsProvider.makeGauge(QUOTA_STORE_SIZE, quotas::size);
  }

  @Override
  public void deleteQuotas() {
    quotas.clear();
  }

  @Override
  public void removeQuota(String role) {
    quotas.remove(role);
  }

  @Override
  public void saveQuota(String role, IResourceAggregate quota) {
    quotas.put(role, quota);
  }

  @Override
  public Optional<IResourceAggregate> fetchQuota(String role) {
    return Optional.ofNullable(quotas.get(role));
  }

  @Override
  public Map<String, IResourceAggregate> fetchQuotas() {
    return ImmutableMap.copyOf(quotas);
  }
}
