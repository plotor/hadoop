/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.deviceframework;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.exceptions.ResourceNotFoundException;
import org.apache.hadoop.yarn.server.nodemanager.Context;
import org.apache.hadoop.yarn.server.nodemanager.api.deviceplugin.Device;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandlerException;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schedule device resource based on requirements and do book keeping
 * It holds all device type resource and can do scheduling as a default
 * scheduler.
 * */
public class DeviceMappingManager {
  static final Log LOG = LogFactory.getLog(DeviceMappingManager.class);

  private Context nmContext;
  private static final int WAIT_MS_PER_LOOP = 1000;

  /**
   * Hold all type of devices.
   * key is the device resource name
   * value is a sorted set of {@link Device}
   * */
  private Map<String, Set<Device>> allAllowedDevices =
      new ConcurrentHashMap<>();

  /**
   * Hold used devices.
   * key is the device resource name
   * value is a sorted map of {@link Device} and {@link ContainerId} pairs
   * */
  private Map<String, Map<Device, ContainerId>> allUsedDevices =
      new ConcurrentHashMap<>();

  public DeviceMappingManager(Context context) {
    nmContext = context;
  }

  @VisibleForTesting
  public Map<String, Set<Device>> getAllAllowedDevices() {
    return allAllowedDevices;
  }

  @VisibleForTesting
  public Map<String, Map<Device, ContainerId>> getAllUsedDevices() {
    return allUsedDevices;
  }

  public synchronized void addDeviceSet(String resourceName,
      Set<Device> deviceSet) {
    LOG.info("Adding new resource: " + "type:"
        + resourceName + "," + deviceSet);
    allAllowedDevices.put(resourceName, new TreeSet<>(deviceSet));
    allUsedDevices.put(resourceName, new TreeMap<>());
  }

  public DeviceAllocation assignDevices(String resourceName,
      Container container)
      throws ResourceHandlerException {
    DeviceAllocation allocation = internalAssignDevices(resourceName,
        container);
    // Wait for a maximum of 120 seconds if no available Devices are there
    // which are yet to be released.
    final int timeoutMsecs = 120 * WAIT_MS_PER_LOOP;
    int timeWaiting = 0;
    while (allocation == null) {
      if (timeWaiting >= timeoutMsecs) {
        break;
      }

      // Sleep for 1 sec to ensure there are some free devices which are
      // getting released.
      try {
        LOG.info("Container : " + container.getContainerId()
            + " is waiting for free " + resourceName + " devices.");
        Thread.sleep(WAIT_MS_PER_LOOP);
        timeWaiting += WAIT_MS_PER_LOOP;
        allocation = internalAssignDevices(resourceName, container);
      } catch (InterruptedException e) {
        // On any interrupt, break the loop and continue execution.
        break;
      }
    }

    if (allocation == null) {
      String message = "Could not get valid " + resourceName
          + " device for container '" + container.getContainerId()
          + "' as some other containers might not releasing them.";
      LOG.warn(message);
      throw new ResourceHandlerException(message);
    }
    return allocation;
  }

  private synchronized DeviceAllocation internalAssignDevices(
      String resourceName, Container container)
      throws ResourceHandlerException {
    Resource requestedResource = container.getResource();
    ContainerId containerId = container.getContainerId();
    int requestedDeviceCount = getRequestedDeviceCount(resourceName,
        requestedResource);
    LOG.debug("Try allocating " + requestedDeviceCount
        + " " + resourceName);
    // Assign devices to container if requested some.
    if (requestedDeviceCount > 0) {
      if (requestedDeviceCount > getAvailableDevices(resourceName)) {
        // If there are some devices which are getting released, wait for few
        // seconds to get it.
        if (requestedDeviceCount <= getReleasingDevices(resourceName)
            + getAvailableDevices(resourceName)) {
          return null;
        }
      }

      int availableDeviceCount = getAvailableDevices(resourceName);
      if (requestedDeviceCount > availableDeviceCount) {
        throw new ResourceHandlerException("Failed to find enough "
            + resourceName
            + ", requestor=" + containerId
            + ", #Requested=" + requestedDeviceCount + ", #available="
            + availableDeviceCount);
      }

      Set<Device> assignedDevices = new TreeSet<>();
      Map<Device, ContainerId> usedDevices = allUsedDevices.get(resourceName);
      Set<Device> allowedDevices = allAllowedDevices.get(resourceName);

      defaultScheduleAction(allowedDevices, usedDevices,
          assignedDevices, containerId, requestedDeviceCount);

      // Record in state store if we allocated anything
      if (!assignedDevices.isEmpty()) {
        try {
          // Update state store.
          nmContext.getNMStateStore().storeAssignedResources(container,
              resourceName,
              new ArrayList<>(assignedDevices));
        } catch (IOException e) {
          cleanupAssignedDevices(resourceName, containerId);
          throw new ResourceHandlerException(e);
        }
      }

      return new DeviceAllocation(resourceName, assignedDevices,
          Sets.difference(allowedDevices, assignedDevices));
    }
    return new DeviceAllocation(resourceName, null,
        allAllowedDevices.get(resourceName));
  }

  public synchronized void recoverAssignedDevices(String resourceName,
      ContainerId containerId)
      throws ResourceHandlerException {
    Container c = nmContext.getContainers().get(containerId);
    Map<Device, ContainerId> usedDevices = allUsedDevices.get(resourceName);
    Set<Device> allowedDevices = allAllowedDevices.get(resourceName);
    if (null == c) {
      throw new ResourceHandlerException(
          "This shouldn't happen, cannot find container with id="
              + containerId);
    }

    for (Serializable deviceSerializable : c.getResourceMappings()
        .getAssignedResources(resourceName)) {
      if (!(deviceSerializable instanceof Device)) {
        throw new ResourceHandlerException(
            "Trying to recover device id, however it"
                + " is not Device instance, this shouldn't happen");
      }

      Device device = (Device) deviceSerializable;

      // Make sure it is in allowed device.
      if (!allowedDevices.contains(device)) {
        throw new ResourceHandlerException(
            "Try to recover device = " + device
                + " however it is not in allowed device list:" + StringUtils
                .join(",", allowedDevices));
      }

      // Make sure it is not occupied by anybody else
      if (usedDevices.containsKey(device)) {
        throw new ResourceHandlerException(
            "Try to recover device id = " + device
                + " however it is already assigned to container="
                + usedDevices.get(device)
                + ", please double check what happened.");
      }

      usedDevices.put(device, containerId);
    }
  }

  public synchronized void cleanupAssignedDevices(String resourceName,
      ContainerId containerId) {
    Iterator<Map.Entry<Device, ContainerId>> iter =
        allUsedDevices.get(resourceName).entrySet().iterator();
    while (iter.hasNext()) {
      if (iter.next().getValue().equals(containerId)) {
        iter.remove();
      }
    }
  }

  public static int getRequestedDeviceCount(String resourceName,
      Resource requestedResource) {
    try {
      return Long.valueOf(requestedResource.getResourceValue(
          resourceName)).intValue();
    } catch (ResourceNotFoundException e) {
      return 0;
    }
  }

  public int getAvailableDevices(String resourceName) {
    return allAllowedDevices.get(resourceName).size()
        - allUsedDevices.get(resourceName).size();
  }

  private long getReleasingDevices(String resourceName) {
    long releasingDevices = 0;
    Map<Device, ContainerId> used = allUsedDevices.get(resourceName);
    Iterator<Map.Entry<Device, ContainerId>> iter = used.entrySet()
        .iterator();
    while (iter.hasNext()) {
      ContainerId containerId = iter.next().getValue();
      Container container = nmContext.getContainers().get(containerId);
      if (container != null) {
        if (container.isContainerInFinalStates()) {
          releasingDevices = releasingDevices + container.getResource()
              .getResourceInformation(resourceName).getValue();
        }
      }
    }
    return releasingDevices;
  }

  // default scheduling logic
  private void defaultScheduleAction(Set<Device> allowed,
      Map<Device, ContainerId> used, Set<Device> assigned,
      ContainerId containerId, int count) {
    LOG.debug("Using default scheduler. Allowed:" + allowed
        + ",Used:" + used + ", containerId:" + containerId);
    for (Device device : allowed) {
      if (!used.containsKey(device)) {
        used.put(device, containerId);
        assigned.add(device);
        if (assigned.size() == count) {
          return;
        }
      }
    } // end for
  }

  static class DeviceAllocation {
    private String resourceName;

    private Set<Device> allowed = Collections.emptySet();
    private Set<Device> denied = Collections.emptySet();

    DeviceAllocation(String resName, Set<Device> a,
        Set<Device> d) {
      this.resourceName = resName;
      if (a != null) {
        this.allowed = ImmutableSet.copyOf(a);
      }
      if (d != null) {
        this.denied = ImmutableSet.copyOf(d);
      }
    }


    public Set<Device> getAllowed() {
      return allowed;
    }

    @Override
    public String toString() {
      return "ResourceType: " + resourceName
          + ", Allowed Devices: " + allowed
          + ", Denied Devices: " + denied;
    }

  }

}
