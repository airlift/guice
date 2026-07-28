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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily creates (and caches) values for keys. If creating the value fails (with errors), an
 * exception is thrown on retrieval, and the failure is cached just like a success would be.
 *
 * <p>This sits on the injector-creation hot path: one instance backs the constructor-injector
 * store and another the members-injector store, and every binding of every injector misses both
 * exactly once. It is a plain ConcurrentHashMap with an in-flight marker per computing key, which
 * keeps the compute-once guarantee without Guava's segment locks or its per-load bookkeeping
 * allocations. Loads may recurse through the cache for other keys, as constructor injectors do for
 * their dependencies; only re-entering the same key on the same thread is broken, exactly as it
 * was with the previous LoadingCache.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
public abstract class FailableCache<K, V>
{
    /**
     * Returned to waiters when the loader died with an unchecked exception.
     */
    private static final Object FAILED = new Object();

    /**
     * Marks a key whose value is being computed; other threads wait on the marker itself. Plain
     * wait/notify instead of a CompletableFuture: a marker is allocated for every load on the
     * injector-creation hot path, while a waiting second thread is the rare case, so the marker
     * must be as cheap as possible.
     */
    private static final class InFlight
    {
        final Thread owner = Thread.currentThread();
        private Object result;

        synchronized void complete(Object value)
        {
            result = value;
            notifyAll();
        }

        synchronized Object await()
        {
            boolean interrupted = false;
            while (result == null) {
                try {
                    wait();
                }
                catch (InterruptedException e) {
                    // uninterruptible, like the CompletableFuture.join it replaces
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return result;
        }
    }

    private final ConcurrentHashMap<K, Object> map = new ConcurrentHashMap<>();

    protected abstract V create(K key, Errors errors) throws ErrorsException;

    public V get(K key, Errors errors)
            throws ErrorsException
    {
        Object value = map.get(key);
        if (value == null) {
            InFlight inFlight = new InFlight();
            Object race = map.putIfAbsent(key, inFlight);
            if (race == null) {
                value = load(key, inFlight);
            }
            else {
                value = race;
            }
        }
        if (value instanceof InFlight otherInFlight) {
            if (otherInFlight.owner == Thread.currentThread()) {
                throw new IllegalStateException("Recursive load of " + key);
            }
            value = otherInFlight.await();
            if (value == FAILED) {
                throw new IllegalStateException("Creation failed for " + key);
            }
        }
        if (value instanceof Errors cachedErrors) {
            errors.merge(cachedErrors);
            throw errors.toException();
        }
        @SuppressWarnings("unchecked") // create returned a non-error result, so this is safe
        V result = (V) value;
        return result;
    }

    private Object load(K key, InFlight inFlight)
    {
        Object computed = null;
        try {
            Errors loadErrors = new Errors();
            V created = null;
            try {
                created = create(key, loadErrors);
            }
            catch (ErrorsException e) {
                loadErrors.merge(e.getErrors());
            }
            computed = loadErrors.hasErrors() ? loadErrors : created;
            return computed;
        }
        finally {
            if (computed != null) {
                map.put(key, computed);
                inFlight.complete(computed);
            }
            else {
                // create threw an unchecked exception; drop the entry so waiters fail rather than
                // hang, and a later get can retry, matching the previous LoadingCache behaviour
                map.remove(key, inFlight);
                inFlight.complete(FAILED);
            }
        }
    }

    boolean remove(K key)
    {
        Object value = map.get(key);
        return !(value instanceof InFlight) && value != null && map.remove(key, value);
    }

    boolean isLoading(K key)
    {
        return map.get(key) instanceof InFlight;
    }

    Map<K, V> asMap()
    {
        ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
        for (Map.Entry<K, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof InFlight) && !(value instanceof Errors)) {
                @SuppressWarnings("unchecked") // create returned a non-error result, so this is safe
                V result = (V) value;
                builder.put(entry.getKey(), result);
            }
        }
        return builder.buildKeepingLast();
    }
}
