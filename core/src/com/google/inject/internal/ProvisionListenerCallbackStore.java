/*
 * Copyright (C) 2011 Google Inc.
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
import com.google.common.collect.ImmutableSet;
import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Stage;
import com.google.inject.spi.ProvisionListener;
import com.google.inject.spi.ProvisionListenerBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * {@link ProvisionListenerStackCallback} for each key.
 *
 * @author sameb@google.com (Sam Berlin)
 */
final class ProvisionListenerCallbackStore
{
    // TODO(sameb): Consider exposing this in the API somehow?  Maybe?
    // Lots of code often want to skip over the internal stuffs.
    private static final ImmutableSet<Key<?>> INTERNAL_BINDINGS =
            ImmutableSet.of(Key.get(Injector.class), Key.get(Stage.class), Key.get(Logger.class));

    private final ImmutableList<ProvisionListenerBinding> listenerBindings;

    /**
     * Plain map instead of a LoadingCache: this store is per injector and get() runs once per
     * binding during injector creation, so during creation every lookup is a cold miss and the
     * LoadingCache machinery (entry future, load stopwatch, key wrapper) was pure overhead. A racy
     * duplicate compute is harmless - callbacks are stateless wrappers over the same listeners.
     */
    private final ConcurrentHashMap<Key<?>, ProvisionListenerStackCallback<?>> cache =
            new ConcurrentHashMap<>();

    ProvisionListenerCallbackStore(List<ProvisionListenerBinding> listenerBindings)
    {
        this.listenerBindings = ImmutableList.copyOf(listenerBindings);
    }

    /**
     * Returns a new {@link ProvisionListenerStackCallback} for the key or {@code null} if there are
     * no listeners
     */
    public <T> ProvisionListenerStackCallback<T> get(Binding<T> binding)
    {
        // With no listeners bound, every callback would be the empty one; skip the cache entirely.
        if (listenerBindings.isEmpty()) {
            return null;
        }
        // Never notify any listeners for internal bindings.
        if (!INTERNAL_BINDINGS.contains(binding.getKey())) {
            ProvisionListenerStackCallback<?> callback = cache.get(binding.getKey());
            if (callback == null) {
                callback = create(binding);
                ProvisionListenerStackCallback<?> race = cache.putIfAbsent(binding.getKey(), callback);
                if (race != null) {
                    callback = race;
                }
            }
            @SuppressWarnings("unchecked")
            // the ProvisionListenerStackCallback type always agrees with the passed type
            ProvisionListenerStackCallback<T> typed = (ProvisionListenerStackCallback<T>) callback;
            return typed.hasListeners() ? typed : null;
        }
        return null;
    }

    /**
     * Purges a key from the cache. Use this only if the type is not actually valid for binding and
     * needs to be purged. (See issue 319 and
     * ImplicitBindingTest#testCircularJitBindingsLeaveNoResidue and
     * #testInstancesRequestingProvidersForThemselvesWithChildInjectors for examples of when this is
     * necessary.)
     *
     * <p>Returns true if the type was stored in the cache, false otherwise.
     */
    boolean remove(Binding<?> type)
    {
        return cache.remove(type.getKey()) != null;
    }

    /**
     * Creates a new {@link ProvisionListenerStackCallback} with the correct listeners for the key.
     */
    private <T> ProvisionListenerStackCallback<T> create(Binding<T> binding)
    {
        List<ProvisionListener> listeners = null;
        for (ProvisionListenerBinding provisionBinding : listenerBindings) {
            if (provisionBinding.getBindingMatcher().matches(binding)) {
                if (listeners == null) {
                    listeners = new ArrayList<>();
                }
                listeners.addAll(provisionBinding.getListeners());
            }
        }
        if (listeners == null || listeners.isEmpty()) {
            // Optimization: don't bother constructing the callback if there are
            // no listeners.
            return ProvisionListenerStackCallback.emptyListener();
        }
        return new ProvisionListenerStackCallback<T>(binding, listeners);
    }
}
