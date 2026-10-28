/*
 * Copyright (C) 2006 Google Inc.
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

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.spi.Element;
import com.google.inject.spi.PrivateElements;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies {@link Binder#rebind(Key) rebinds} by removing the bindings they replace, before any
 * binding is processed. A rebind replaces the bindings of its key that precede it in the same
 * injector or private environment, so the replaced bindings have no effect at all: they aren't
 * validated, initialized or injected. A rebind with nothing to replace is reported and removed as
 * well.
 */
final class RebindProcessor
{
    private final Errors errors;

    RebindProcessor(Errors errors)
    {
        this.errors = errors;
    }

    /**
     * @param parent the injector of the enclosing environment, or {@code null} for a root injector
     * @param elements the elements of one injector or private environment, in the order they were
     *         recorded
     */
    void process(InjectorImpl parent, List<Element> elements)
    {
        Set<Key<?>> reboundKeys = new HashSet<>();
        for (Element element : elements) {
            if (element instanceof BindingImpl<?> binding && binding.isRebind()) {
                reboundKeys.add(binding.getKey());
            }
        }
        if (reboundKeys.isEmpty()) {
            return;
        }

        // for each rebound key, the bindings that the next rebind of it replaces
        Map<Key<?>, List<Integer>> replaceable = new HashMap<>();
        BitSet removed = new BitSet();
        for (int i = 0; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof BindingImpl<?> binding)
                    || !reboundKeys.contains(binding.getKey())) {
                continue;
            }
            List<Integer> bindings = replaceable.computeIfAbsent(binding.getKey(), _ -> new ArrayList<>());
            if (binding.isRebind()) {
                if (bindings.isEmpty()) {
                    reportNothingToReplace(parent, elements, i);
                    removed.set(i);
                    continue;
                }
                bindings.forEach(removed::set);
                bindings.clear();
            }
            bindings.add(i);
        }

        for (int i = removed.length() - 1; i >= 0; i = removed.previousSetBit(i - 1)) {
            elements.remove(i);
        }
    }

    private void reportNothingToReplace(InjectorImpl parent, List<Element> elements, int rebindIndex)
    {
        BindingImpl<?> rebind = (BindingImpl<?>) elements.get(rebindIndex);
        Key<?> key = rebind.getKey();
        Errors rebindErrors = errors.withSource(rebind.getSource());

        // the most likely mistake: the rebind was recorded before the binding it was meant for
        for (int i = rebindIndex + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof BindingImpl<?> binding
                    && !binding.isRebind()
                    && binding.getKey().equals(key)) {
                rebindErrors.rebindBeforeBinding(key, binding.getSource());
                return;
            }
        }
        boolean exposed = elements.stream()
                .anyMatch(element -> element instanceof PrivateElements privateElements
                        && privateElements.getExposedKeys().contains(key));
        if (exposed) {
            rebindErrors.rebindOfExposedBinding(key);
        }
        else if (parent != null && parent.getBindingData().getExplicitBinding(key) != null) {
            rebindErrors.rebindOfParentBinding(key);
        }
        else {
            rebindErrors.rebindWithoutBinding(key);
        }
    }
}
