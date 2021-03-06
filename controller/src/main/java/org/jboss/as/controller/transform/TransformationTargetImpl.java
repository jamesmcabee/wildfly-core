/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.transform;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.OperationTransformerRegistry;
import org.jboss.as.controller.registry.OperationTransformerRegistry.PlaceholderResolver;


/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a>
 */
public class TransformationTargetImpl implements TransformationTarget {

    private final ModelVersion version;
    private final TransformerRegistry transformerRegistry;
    private final Map<String, ModelVersion> subsystemVersions = Collections.synchronizedMap(new HashMap<String, ModelVersion>());
    private final OperationTransformerRegistry registry;
    private final TransformationTargetType type;
    private final IgnoredTransformationRegistry transformationExclusion;
    private final RuntimeIgnoreTransformation runtimeIgnoreTransformation;
    private final PlaceholderResolver placeholderResolver;

    private TransformationTargetImpl(final TransformerRegistry transformerRegistry, final ModelVersion version,
                                     final Map<PathAddress, ModelVersion> subsystemVersions, final OperationTransformerRegistry transformers,
                                     final IgnoredTransformationRegistry transformationExclusion, final TransformationTargetType type,
                                     final RuntimeIgnoreTransformation runtimeIgnoreTransformation, final PlaceholderResolver placeholderResolver) {
        this.version = version;
        this.transformerRegistry = transformerRegistry;
        for (Map.Entry<PathAddress, ModelVersion> p : subsystemVersions.entrySet()) {
            final String name = p.getKey().getLastElement().getValue();
            this.subsystemVersions.put(name, p.getValue());
        }
        this.registry = transformers;
        this.type = type;
        this.transformationExclusion = transformationExclusion == null ? null : transformationExclusion;
        this.runtimeIgnoreTransformation = runtimeIgnoreTransformation;
        this.placeholderResolver = placeholderResolver;
    }

    private TransformationTargetImpl(final TransformationTargetImpl target, final PlaceholderResolver placeholderResolver) {
        this.version = target.version;
        this.transformerRegistry = target.transformerRegistry;
        this.subsystemVersions.putAll(target.subsystemVersions);
        this.registry = target.registry;
        this.type = target.type;
        this.transformationExclusion = target.transformationExclusion;
        this.runtimeIgnoreTransformation = target.runtimeIgnoreTransformation;
        this.placeholderResolver = placeholderResolver;
    }

    public static TransformationTargetImpl create(final TransformerRegistry transformerRegistry, final ModelVersion version,
                                                  final Map<PathAddress, ModelVersion> subsystems,
                                                  final IgnoredTransformationRegistry transformationExclusion, final TransformationTargetType type,
                                                  final RuntimeIgnoreTransformation runtimeIgnoreTransformation) {
        final OperationTransformerRegistry registry;
        switch (type) {
            case SERVER:
                registry = transformerRegistry.resolveServer(version, subsystems);
                break;
            default:
                registry = transformerRegistry.resolveHost(version, subsystems);
        }
        return new TransformationTargetImpl(transformerRegistry, version, subsystems, registry, transformationExclusion, type, runtimeIgnoreTransformation, null);
    }

    TransformationTargetImpl copyWithplaceholderResolver(final PlaceholderResolver placeholderResolver) {
        return new TransformationTargetImpl(this, placeholderResolver);
    }

    @Override
    public ModelVersion getVersion() {
        return version;
    }

    @Override
    public ModelVersion getSubsystemVersion(String subsystemName) {
        return subsystemVersions.get(subsystemName);
    }

    @Override
    public ResourceTransformer resolveTransformer(ResourceTransformationContext context, final PathAddress address ) {
        if (ignoreResourceTransformation(context, address)) {
            return ResourceTransformer.DISCARD;
        }
        OperationTransformerRegistry.ResourceTransformerEntry entry = registry.resolveResourceTransformer(address, placeholderResolver);
        if(entry == null) {
            return ResourceTransformer.DEFAULT;
        }
        return entry.getTransformer();
    }

    @Override
    public TransformerEntry getTransformerEntry(TransformationContext context, final PathAddress address) {
        if (ignoreResourceTransformation(context, address)) {
            return TransformerEntry.DISCARD;
        }
        return registry.getTransformerEntry(address, placeholderResolver);
    }

    @Override
    public List<PathAddressTransformer> getPathTransformation(final PathAddress address) {
        return registry.getPathTransformations(address, placeholderResolver);
    }

    @Override
    public OperationTransformer resolveTransformer(TransformationContext context, final PathAddress address, final String operationName) {
        if (ignoreResourceTransformation(context, address)) {
            return OperationTransformer.DEFAULT;
        }
        if(address.size() == 0) {
            // TODO use registry registry to register this operations.
            if(ModelDescriptionConstants.COMPOSITE.equals(operationName)) {
                return new CompositeOperationTransformer();
            }
        }
        final OperationTransformerRegistry.OperationTransformerEntry entry = registry.resolveOperationTransformer(address, operationName, placeholderResolver);
        return entry.getTransformer();
    }

    @Override
    public void addSubsystemVersion(String subsystemName, int majorVersion, int minorVersion) {
        addSubsystemVersion(subsystemName, ModelVersion.create(majorVersion, minorVersion));
    }

    @Override
    public void addSubsystemVersion(final String subsystemName, final ModelVersion version) {
        this.subsystemVersions.put(subsystemName, version);
        transformerRegistry.addSubsystem(registry, subsystemName, version);
    }

    @Override
    public TransformationTargetType getTargetType() {
        return type;
    }

    @Override
    public String getHostName() {
        if (transformationExclusion == null) {
            return null;
        }
        return transformationExclusion.getHostName();
    }

    @Override
    public boolean isIgnoredResourceListAvailableAtRegistration() {
        return version.getMajor() >= 1 && version.getMinor() >= 4;
    }

    private boolean ignoreResourceTransformation(TransformationContext context, PathAddress address) {
        if (transformationExclusion != null && transformationExclusion.isResourceTransformationIgnored(address)) {
            return true;
        }
        if (!context.isSkipRuntimeIgnoreCheck() && runtimeIgnoreTransformation != null && runtimeIgnoreTransformation.ignoreResource(context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS), address)) {
            return true;
        }
        return false;
    }
}
