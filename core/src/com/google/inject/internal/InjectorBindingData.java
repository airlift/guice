/*
 * Copyright (C) 2008 Google Inc.
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

package com.google.inject.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.inject.Binding;
import com.google.inject.Key;
import com.google.inject.Scope;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.InjectionRequest;
import com.google.inject.spi.InterceptorBinding;
import com.google.inject.spi.MembersInjectorLookup;
import com.google.inject.spi.ModuleAnnotatedMethodScannerBinding;
import com.google.inject.spi.ProviderLookup;
import com.google.inject.spi.ProvisionListenerBinding;
import com.google.inject.spi.ScopeBinding;
import com.google.inject.spi.StaticInjectionRequest;
import com.google.inject.spi.TypeConverterBinding;
import com.google.inject.spi.TypeListenerBinding;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A container that stores an injector's binding data. This excludes JIT binding data, which is
 * stored in {@link InjectorJitBindingData}.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
class InjectorBindingData
{
    // The parent injector's InjectorBindingData, if the parent injector exists.
    private final Optional<InjectorBindingData> parent;

    // Must be a linked hashmap in order to preserve order of bindings in Modules.
    private final Map<Key<?>, Binding<?>> explicitBindingsMutable = new LinkedHashMap<>();
    private final Map<Key<?>, Binding<?>> explicitBindings =
            Collections.unmodifiableMap(explicitBindingsMutable);
    private final Map<Class<? extends Annotation>, ScopeBinding> scopes = new HashMap<>();
    private final Set<ProviderLookup<?>> providerLookups = new LinkedHashSet<>();
    private final Set<StaticInjectionRequest> staticInjectionRequests = new LinkedHashSet<>();
    private final Set<MembersInjectorLookup<?>> membersInjectorLookups = new LinkedHashSet<>();
    private final Set<InjectionRequest<?>> injectionRequests = new LinkedHashSet<>();
    private final List<TypeConverterBinding> converters = new ArrayList<>();
    private final List<InterceptorBinding> interceptorBindings = new ArrayList<>();
    private final List<TypeListenerBinding> typeListenerBindings = new ArrayList<>();
    private final List<ProvisionListenerBinding> provisionListenerBindings = new ArrayList<>();
    private final List<ModuleAnnotatedMethodScannerBinding> scannerBindings = new ArrayList<>();
    // The injector's explicit bindings, indexed by the binding's type. Built lazily on first use:
    // only multibinders, the servlet pipelines, and the public findBindingsByType API consult it,
    // and all of them run after the explicit-binding set is final, so most injectors never pay for
    // indexing every binding. Benign race: concurrent first calls build identical immutable maps.
    private volatile ImmutableListMultimap<TypeLiteral<?>, Binding<?>> indexedExplicitBindings;

    InjectorBindingData(Optional<InjectorBindingData> parent)
    {
        this.parent = parent;
    }

    public Optional<InjectorBindingData> parent()
    {
        return parent;
    }

    @SuppressWarnings("unchecked") // we only put in BindingImpls that match their key types
    public <T> BindingImpl<T> getExplicitBinding(Key<T> key)
    {
        Binding<?> binding = explicitBindings.get(key);
        if (binding == null && parent.isPresent()) {
            return parent.orElseThrow().getExplicitBinding(key);
        }
        return (BindingImpl<T>) binding;
    }

    public Map<Key<?>, Binding<?>> getExplicitBindingsThisLevel()
    {
        return explicitBindings;
    }

    public void putBinding(Key<?> key, BindingImpl<?> binding)
    {
        explicitBindingsMutable.put(key, binding);
    }

    public void putProviderLookup(ProviderLookup<?> lookup)
    {
        providerLookups.add(lookup);
    }

    public Set<ProviderLookup<?>> getProviderLookupsThisLevel()
    {
        return providerLookups;
    }

    public void putStaticInjectionRequest(StaticInjectionRequest staticInjectionRequest)
    {
        staticInjectionRequests.add(staticInjectionRequest);
    }

    public Set<StaticInjectionRequest> getStaticInjectionRequestsThisLevel()
    {
        return staticInjectionRequests;
    }

    public void putInjectionRequest(InjectionRequest<?> injectionRequest)
    {
        injectionRequests.add(injectionRequest);
    }

    public Set<InjectionRequest<?>> getInjectionRequestsThisLevel()
    {
        return injectionRequests;
    }

    public void putMembersInjectorLookup(MembersInjectorLookup<?> membersInjectorLookup)
    {
        membersInjectorLookups.add(membersInjectorLookup);
    }

    public Set<MembersInjectorLookup<?>> getMembersInjectorLookupsThisLevel()
    {
        return membersInjectorLookups;
    }

    public ScopeBinding getScopeBinding(Class<? extends Annotation> annotationType)
    {
        ScopeBinding scopeBinding = scopes.get(annotationType);
        if (scopeBinding == null && parent.isPresent()) {
            return parent.orElseThrow().getScopeBinding(annotationType);
        }
        return scopeBinding;
    }

    public void putScopeBinding(Class<? extends Annotation> annotationType, ScopeBinding scope)
    {
        scopes.put(annotationType, scope);
    }

    public Collection<ScopeBinding> getScopeBindingsThisLevel()
    {
        return scopes.values();
    }

    public Iterable<TypeConverterBinding> getConvertersThisLevel()
    {
        return converters;
    }

    public void addConverter(TypeConverterBinding typeConverterBinding)
    {
        converters.add(typeConverterBinding);
    }

    public TypeConverterBinding getConverter(
            String stringValue,
            TypeLiteral<?> type,
            Errors errors,
            Object source)
    {
        TypeConverterBinding matchingConverter = null;
        InjectorBindingData b = this;
        while (b != null) {
            for (TypeConverterBinding converter : b.getConvertersThisLevel()) {
                if (converter.getTypeMatcher().matches(type)) {
                    if (matchingConverter != null) {
                        errors.ambiguousTypeConversion(stringValue, source, type, matchingConverter, converter);
                    }
                    matchingConverter = converter;
                }
            }
            b = b.parent().orElse(null);
        }
        return matchingConverter;
    }

    public void addInterceptorBinding(InterceptorBinding interceptorBinding)
    {
        interceptorBindings.add(interceptorBinding);
    }

    public ImmutableList<InterceptorBinding> getInterceptorBindings()
    {
        if (parent.isPresent()) {
            return new ImmutableList.Builder<InterceptorBinding>()
                    .addAll(parent.orElseThrow().getInterceptorBindings())
                    .addAll(interceptorBindings)
                    .build();
        }
        return ImmutableList.copyOf(interceptorBindings);
    }

    public ImmutableList<InterceptorBinding> getInterceptorBindingsThisLevel()
    {
        return ImmutableList.copyOf(interceptorBindings);
    }

    public void addTypeListener(TypeListenerBinding listenerBinding)
    {
        typeListenerBindings.add(listenerBinding);
    }

    public ImmutableList<TypeListenerBinding> getTypeListenerBindings()
    {
        if (parent.isPresent()) {
            return new ImmutableList.Builder<TypeListenerBinding>()
                    .addAll(parent.orElseThrow().getTypeListenerBindings())
                    .addAll(typeListenerBindings)
                    .build();
        }
        return ImmutableList.copyOf(typeListenerBindings);
    }

    public ImmutableList<TypeListenerBinding> getTypeListenerBindingsThisLevel()
    {
        return ImmutableList.copyOf(typeListenerBindings);
    }

    public void addProvisionListener(ProvisionListenerBinding listenerBinding)
    {
        provisionListenerBindings.add(listenerBinding);
    }

    public ImmutableList<ProvisionListenerBinding> getProvisionListenerBindings()
    {
        if (parent.isPresent()) {
            return new ImmutableList.Builder<ProvisionListenerBinding>()
                    .addAll(parent.orElseThrow().getProvisionListenerBindings())
                    .addAll(provisionListenerBindings)
                    .build();
        }
        return ImmutableList.copyOf(provisionListenerBindings);
    }

    public ImmutableList<ProvisionListenerBinding> getProvisionListenerBindingsThisLevel()
    {
        return ImmutableList.copyOf(provisionListenerBindings);
    }

    public void addScanner(ModuleAnnotatedMethodScannerBinding scanner)
    {
        scannerBindings.add(scanner);
    }

    public ImmutableList<ModuleAnnotatedMethodScannerBinding> getScannerBindings()
    {
        if (parent.isPresent()) {
            return new ImmutableList.Builder<ModuleAnnotatedMethodScannerBinding>()
                    .addAll(parent.orElseThrow().getScannerBindings())
                    .addAll(scannerBindings)
                    .build();
        }
        return ImmutableList.copyOf(scannerBindings);
    }

    public ImmutableList<ModuleAnnotatedMethodScannerBinding> getScannerBindingsThisLevel()
    {
        return ImmutableList.copyOf(scannerBindings);
    }

    public Map<Class<? extends Annotation>, Scope> getScopes()
    {
        ImmutableMap.Builder<Class<? extends Annotation>, Scope> builder = ImmutableMap.builder();
        for (Map.Entry<Class<? extends Annotation>, ScopeBinding> entry : scopes.entrySet()) {
            builder.put(entry.getKey(), entry.getValue().getScope());
        }
        return builder.buildOrThrow();
    }

    public ListMultimap<TypeLiteral<?>, Binding<?>> getIndexedExplicitBindings()
    {
        ImmutableListMultimap<TypeLiteral<?>, Binding<?>> index = indexedExplicitBindings;
        if (index == null) {
            ImmutableListMultimap.Builder<TypeLiteral<?>, Binding<?>> builder =
                    ImmutableListMultimap.builder();
            for (Binding<?> binding : getExplicitBindingsThisLevel().values()) {
                builder.put(binding.getKey().getTypeLiteral(), binding);
            }
            index = builder.build();
            indexedExplicitBindings = index;
        }
        return index;
    }
}
