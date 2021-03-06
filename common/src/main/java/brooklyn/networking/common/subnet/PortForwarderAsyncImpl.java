/*
 * Copyright 2013-2015 by Cloudsoft Corporation Limited
 *
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
package brooklyn.networking.common.subnet;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;

import brooklyn.networking.AttributeMunger;

public class PortForwarderAsyncImpl implements PortForwarderAsync {

    private static final Logger log = LoggerFactory.getLogger(PortForwarderAsyncImpl.class);

    private final EntityLocal adjunctEntity;
    private final PortForwarder portForwarder;

    public PortForwarderAsyncImpl(EntityLocal adjunctEntity, PortForwarder portForwarder, PortForwardManager portForwardManager) {
        this.adjunctEntity = adjunctEntity;
        this.portForwarder = portForwarder;
    }

    @Override
    public void openGatewayAsync(EntityAndAttribute<String> whereToAdvertiseHostname) {
        // IP of port-forwarder already exists; can call synchronously
        String gateway = portForwarder.openGateway();
        AttributeMunger.setAttributeIfChanged(whereToAdvertiseHostname, gateway);
    }

    @Override
    public void openStaticNatAsync(Entity serviceToOpen, EntityAndAttribute<String> whereToAdvertiseHostname) {
        // FIXME Do in deferred block; what do we wait for?
        String staticNat = portForwarder.openStaticNat(serviceToOpen);
        whereToAdvertiseHostname.setValue(staticNat);
    }

    @Override
    public void openFirewallPortAsync(EntityAndAttribute<String> publicIp, int port, Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRangeAsync(publicIp, PortRanges.fromInteger(port), protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRangeAsync(final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
        DeferredExecutor<String> updater = new DeferredExecutor<String>("open-firewall", publicIp, Predicates.notNull(), new Runnable() {
            public void run() {
                portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
            }});
        subscribe(publicIp.getEntity(), publicIp.getAttribute(), updater);
        updater.apply(publicIp.getEntity(), publicIp.getValue());
    }

    @Override
    public void openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> source, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr) {
        EntityAndAttribute<Integer> privatePort = source;
        DeferredExecutor<Integer> updater = new DeferredExecutor<>("open-port-forwarding", privatePort, Predicates.notNull(), new Runnable() {
            private MachineAndPort updated = null;
            public void run() {
                Entity entity = source.getEntity();
                Integer privatePortVal = source.getValue();
                if (privatePortVal == null) {
                    if (log.isDebugEnabled()) log.debug("Private port null for entity {}; not opening or advertising mapped port", entity);
                    return;
                }
                Maybe<MachineLocation> machineLocationMaybe = Machines.findUniqueMachineLocation(entity.getLocations());
                if (machineLocationMaybe.isAbsent()) {
                    if (log.isDebugEnabled()) log.debug("No machine found for entity {}; not opening or advertising mapped port", entity);
                    return;
                }
                MachineLocation machine = machineLocationMaybe.get();
                MachineAndPort machineAndPort = new MachineAndPort(machine, privatePortVal);
                if (updated != null && machineAndPort.equals(updated)) {
                    if (log.isDebugEnabled()) log.debug("Already created port-mapping for entity {}, at {} -> {}; not opening again", new Object[] {entity, machine, privatePortVal});
                    return;
                }
                
                HostAndPort publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);
                if (publicEndpoint == null) {
                    log.warn("No host:port obtained for "+machine+" -> "+privatePortVal+"; not advertising mapped port");
                    return;
                }
                
                // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
                String sensorToAdvertise = source.getAttribute().getName();
                portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
                AttributeSensor<String> mappedSensor = Sensors.newStringSensor("mapped." + sensorToAdvertise);
                AttributeSensor<String> mappedEndpointSensor = Sensors.newStringSensor("mapped.endpoint." + sensorToAdvertise);
                AttributeSensor<Integer> mappedPortSensor = Sensors.newIntegerSensor("mapped.portPart." + sensorToAdvertise);
                String endpoint = publicEndpoint.getHostText() + ":" + publicEndpoint.getPort();
                entity.sensors().set(mappedSensor, endpoint);
                entity.sensors().set(mappedEndpointSensor, endpoint);
                entity.sensors().set(mappedPortSensor, publicEndpoint.getPort());
                updated = machineAndPort;
            }});
        
        subscribe(ImmutableMap.of("notifyOfInitialValue", Boolean.TRUE), privatePort.getEntity(), privatePort.getAttribute(), updater);
        subscribe(privatePort.getEntity(), AbstractEntity.LOCATION_ADDED, updater);
    }

    @Override
    public void openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> privatePort, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        DeferredExecutor<Integer> updater = new DeferredExecutor<>("open-port-forwarding", privatePort, Predicates.notNull(), new Runnable() {
            public void run() {
                Entity entity = privatePort.getEntity();
                Integer privatePortVal = privatePort.getValue();
                Maybe<MachineLocation> machineLocationMaybe = Machines.findUniqueMachineLocation(entity.getLocations());
                if (machineLocationMaybe.isAbsent()) {
                    return;
                }
                MachineLocation machine = machineLocationMaybe.get();
                HostAndPort publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);

                // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
                portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
                whereToAdvertiseEndpoint.setValue(publicEndpoint.getHostText()+":"+publicEndpoint.getPort());
            }});
        subscribe(privatePort.getEntity(), privatePort.getAttribute(), updater);
        subscribe(privatePort.getEntity(), AbstractEntity.LOCATION_ADDED, updater);
        updater.apply(privatePort.getEntity(), privatePort.getValue());
    }

    protected <T> void subscribe(Entity entity, Sensor<T> attribute, SensorEventListener<? super T> listener) {
        adjunctEntity.subscriptions().subscribe(entity, attribute, listener);
    }

    protected <T> void subscribe(Map<String, ?> flags, Entity entity, Sensor<T> attribute, SensorEventListener<? super T> listener) {
        adjunctEntity.subscriptions().subscribe(flags, entity, attribute, listener);
    }

    protected class DeferredExecutor<T> implements SensorEventListener<Object> {
        private final EntityAndAttribute<T> attribute;
        private final Predicate<? super T> readiness;
        private final Runnable task;
        private final String description;

        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Runnable task) {
            this(description, attribute, Predicates.notNull(), task);
        }

        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Predicate<? super T> readiness, Runnable task) {
            this.description = description;
            this.attribute = attribute;
            this.readiness = readiness;
            this.task = task;
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }

        public void apply(Entity source, Object valueIgnored) {
            T val = (T) attribute.getValue();
            if (!readiness.apply(val)) {
                log.warn("Skipping {} for {} because attribute {} not ready", new Object[] {description, attribute.getEntity(), attribute.getAttribute()});
                return;
            }

            task.run();
        }
    }
    
    private static class MachineAndPort {
        private final MachineLocation machine;
        private final int port;

        MachineAndPort(MachineLocation machine, int port) {
            this.machine = checkNotNull(machine, "machine");
            this.port = port;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MachineAndPort)) return false;
            MachineAndPort o = (MachineAndPort) obj;
            return machine.equals(o.machine) && port == o.port;
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(machine, port);
        }
        
        @Override
        public String toString() {
            return machine+" -> "+port;
        }
    }
}
