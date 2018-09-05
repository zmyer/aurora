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
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.gen.Attribute;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;

/**
 * An in-memory attribute store.
 */
class MemAttributeStore implements AttributeStore.Mutable {
  @VisibleForTesting
  static final String ATTRIBUTE_STORE_SIZE = "mem_storage_attribute_size";

  private final Map<String, IHostAttributes> hostAttributes = Maps.newConcurrentMap();

  @Inject
  MemAttributeStore(StatsProvider statsProvider) {
    statsProvider.makeGauge(ATTRIBUTE_STORE_SIZE, hostAttributes::size);
  }

  @Override
  public void deleteHostAttributes() {
    hostAttributes.clear();
  }

  @Override
  public boolean saveHostAttributes(IHostAttributes attributes) {
    Preconditions.checkArgument(
        FluentIterable.from(attributes.getAttributes()).allMatch(a -> !a.getValues().isEmpty()));
    Preconditions.checkArgument(attributes.isSetMode());

    IHostAttributes previous = hostAttributes.put(
        attributes.getHost(),
        merge(attributes, Optional.ofNullable(hostAttributes.get(attributes.getHost()))));
    return !attributes.equals(previous);
  }

  private IHostAttributes merge(IHostAttributes newAttributes, Optional<IHostAttributes> previous) {
    HostAttributes attributes = newAttributes.newBuilder();
    if (!attributes.isSetMode()) {
      // If the newly-saved value does not explicitly set the mode, use the previous value
      // or the default.
      MaintenanceMode mode;
      if (previous.isPresent() && previous.get().isSetMode()) {
        mode = previous.get().getMode();
      } else {
        mode = MaintenanceMode.NONE;
      }
      attributes.setMode(mode);
    }
    if (!attributes.isSetAttributes()) {
      attributes.setAttributes(ImmutableSet.<Attribute>of());
    }
    return IHostAttributes.build(attributes);
  }

  @Override
  public Optional<IHostAttributes> getHostAttributes(String host) {
    return Optional.ofNullable(hostAttributes.get(host));
  }

  @Override
  public Set<IHostAttributes> getHostAttributes() {
    return ImmutableSet.copyOf(hostAttributes.values());
  }
}
