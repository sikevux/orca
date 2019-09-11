/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.monitoreddeploy;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.config.DeploymentMonitorServiceProvider;
import com.netflix.spinnaker.orca.ExecutionStatus;
import com.netflix.spinnaker.orca.TaskResult;
import com.netflix.spinnaker.orca.deploymentmonitor.models.EvaluateHealthRequest;
import com.netflix.spinnaker.orca.deploymentmonitor.models.EvaluateHealthResponse;
import com.netflix.spinnaker.orca.deploymentmonitor.models.StatusReason;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "monitored-deploy.enabled")
public class EvaluateDeploymentHealthTask extends MonitoredDeployBaseTask {
  @Autowired
  EvaluateDeploymentHealthTask(
      DeploymentMonitorServiceProvider deploymentMonitorServiceProvider, Registry registry) {
    super(deploymentMonitorServiceProvider, registry);
  }

  @Override
  public @Nonnull TaskResult executeInternal() {
    EvaluateHealthRequest request = new EvaluateHealthRequest(stage);

    log.info(
        "Evaluating health of deployment {} at {}% with {}",
        request.getNewServerGroup(), request.getCurrentProgress(), monitorDefinition);

    EvaluateHealthResponse response = monitorDefinition.getService().evaluateHealth(request);
    sanitizeAndLogResponse(response);

    EvaluateHealthResponse.NextStepDirective directive = response.getNextStep().getDirective();

    Id statusCounterId =
        registry
            .createId("deploymentMonitor.healthStatus")
            .withTag("monitorId", monitorDefinition.getId())
            .withTag("status", directive.toString());

    registry.counter(statusCounterId).increment();

    if (directive != EvaluateHealthResponse.NextStepDirective.WAIT) {
      // We want to log the time it takes for the monitor to actually produce a decision
      // since wait is a not a decision - no need to time it
      Id timerId =
          registry
              .createId("deploymentMonitor.timing")
              .withTag("monitorId", monitorDefinition.getId())
              .withTag("status", directive.toString());

      long duration = Instant.now().toEpochMilli() - stage.getStartTime();
      registry.timer(timerId).record(duration, TimeUnit.MILLISECONDS);
    }

    List<StatusReason> statusReasons =
        Optional.ofNullable(response.getStatusReasons()).orElse(Collections.emptyList());

    return processDirective(directive).context("deploymentMonitorReasons", statusReasons).build();
  }

  private TaskResult.TaskResultBuilder processDirective(
      EvaluateHealthResponse.NextStepDirective directive) {
    switch (directive) {
      case COMPLETE:
        // TODO(mvulfson): Actually implement this case in the stages
        return TaskResult.builder(ExecutionStatus.SUCCEEDED).output("skipToPercentage", 100);

      case CONTINUE:
        return TaskResult.builder(ExecutionStatus.SUCCEEDED);

      case WAIT:
        return TaskResult.builder(ExecutionStatus.RUNNING);

      case ABORT:
        return TaskResult.builder(ExecutionStatus.TERMINAL);

      default:
        throw new DeploymentMonitorInvalidDataException(
            String.format(
                "Invalid next step directive: %s received from Deployment Monitor: %s",
                directive, monitorDefinition));
    }
  }
}
