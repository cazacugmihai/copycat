/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.state;

import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.internal.coordinator.ClusterCoordinator;
import net.kuujo.copycat.cluster.internal.coordinator.CoordinatorConfig;
import net.kuujo.copycat.cluster.internal.coordinator.DefaultClusterCoordinator;
import net.kuujo.copycat.resource.Resource;

/**
 * State machine.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface StateMachine<T> extends Resource<StateMachine<T>> {

  /**
   * Creates a new state machine.
   *
   * @param name The state machine resource name.
   * @param uri The state machine member URI.
   * @param stateType The state machine state type.
   * @param initialState The state machine state.
   * @param cluster The state machine cluster configuration.
   * @return The state machine.
   */
  static <T> StateMachine<T> create(String name, String uri, Class<T> stateType, Class<? extends T> initialState, ClusterConfig cluster) {
    return create(name, uri, cluster, new StateMachineConfig().withStateType(stateType).withInitialState(initialState));
  }

  /**
   * Creates a new state machine.
   *
   * @param name The state machine resource name.
   * @param uri The state machine member URI.
   * @param cluster The state machine cluster configuration.
   * @param config The state machine configuration.
   * @return The state machine.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static <T> StateMachine<T> create(String name, String uri, ClusterConfig cluster, StateMachineConfig config) {
    ClusterCoordinator coordinator = new DefaultClusterCoordinator(uri, new CoordinatorConfig().withClusterConfig(cluster));
    return coordinator.<StateMachine<T>>getResource(name, config.resolve(cluster))
      .addStartupTask(() -> coordinator.open().thenApply(v -> null))
      .addShutdownTask(coordinator::close);
  }

  /**
   * Creates a state machine proxy.
   *
   * @param type The proxy interface.
   * @param <U> The proxy type.
   * @return The proxy object.
   */
  <U> U createProxy(Class<U> type);

}
