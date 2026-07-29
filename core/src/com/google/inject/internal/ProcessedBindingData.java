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

import com.google.inject.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of creation listeners and uninitialized bindings, so they can be processed after
 * bindings are recorded.
 *
 * @author sameb@google.com (Sam Berlin)
 */
class ProcessedBindingData
{
    private final List<CreationListener> creationListeners = new ArrayList<>();
    private final List<DeferredParentBan> deferredParentBans = new ArrayList<>();

    /**
     * A key ban recorded during element processing, which runs outside the family creation lock,
     * to be applied under the lock before initialization. The banned-key set is shared family
     * state, so writes may only happen while the lock is held.
     */
    private record DeferredParentBan(
            InjectorJitBindingData jitBindingData,
            Key<?> key,
            InjectorBindingData bindingData,
            Object source) {}

    /**
     * Bans the key in the injector's ancestors, walking upward. Ancestors that are part of the
     * current creation are thread-local state and are banned immediately, preserving the exact
     * ordering semantics sibling private environments rely on within one creation. The first
     * completed ancestor and everything above it is shared family state: that segment is recorded
     * and applied by {@link #applyDeferredParentBans} under the family creation lock.
     */
    void banKeyInParentOrDefer(
            InjectorJitBindingData jitBindingData,
            Key<?> key,
            InjectorBindingData bindingData,
            Object source)
    {
        InjectorJitBindingData ancestor = jitBindingData.parentJitData().orElse(null);
        while (ancestor != null) {
            if (ancestor.isCreationComplete()) {
                deferredParentBans.add(new DeferredParentBan(ancestor, key, bindingData, source));
                return;
            }
            ancestor.banKeyLocally(key, bindingData, source);
            ancestor = ancestor.parentJitData().orElse(null);
        }
    }

    /**
     * Applies the shared-ancestor segments of bans recorded during unlocked processing. Must be
     * called with the family creation lock held. Re-checks for just-in-time bindings that appeared
     * in a completed ancestor after the processing-time check: the ban is too late for those, which
     * is the same conflict the processing-time check reports when it wins the race.
     */
    void applyDeferredParentBans(Errors errors)
    {
        for (DeferredParentBan ban : deferredParentBans) {
            for (InjectorJitBindingData ancestor = ban.jitBindingData;
                    ancestor != null;
                    ancestor = ancestor.parentJitData().orElse(null)) {
                if (ancestor.getJitBinding(ban.key) != null) {
                    errors.jitBindingAlreadySet(ban.key);
                    break;
                }
                ancestor.banKeyLocally(ban.key, ban.bindingData, ban.source);
            }
        }
        deferredParentBans.clear();
    }

    private final List<Runnable> uninitializedBindings = new ArrayList<>();
    private final List<Runnable> delayedUninitializedBindings = new ArrayList<>();

    void addCreationListener(CreationListener listener)
    {
        creationListeners.add(listener);
    }

    void addUninitializedBinding(Runnable runnable)
    {
        uninitializedBindings.add(runnable);
    }

    void addDelayedUninitializedBinding(Runnable runnable)
    {
        delayedUninitializedBindings.add(runnable);
    }

    /**
     * Initialize bindings. This may be done eagerly
     */
    void initializeBindings()
    {
        for (Runnable initializer : uninitializedBindings) {
            initializer.run();
        }
    }

    /**
     * Runs creation listeners.
     *
     * <p>TODO(lukes): figure out exactly why this case exists.
     */
    void runCreationListeners(Errors errors)
    {
        for (CreationListener creationListener : creationListeners) {
            creationListener.notify(errors);
        }
    }

    /**
     * Initialized bindings that need to be delayed until after all injection points and other
     * bindings are processed. The main current usecase for this is resolving Optional dependencies
     * for OptionalBinder bindings.
     */
    void initializeDelayedBindings()
    {
        for (Runnable initializer : delayedUninitializedBindings) {
            initializer.run();
        }
    }
}
