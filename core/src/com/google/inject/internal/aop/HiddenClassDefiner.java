/*
 * Copyright (C) 2021 Google Inc.
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

package com.google.inject.internal.aop;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;

/**
 * {@link ClassDefiner} that defines classes as hidden nest-mates of the host class.
 *
 * <p>Hidden classes cannot be looked up by name, but they can be unloaded independently of their
 * host and are cheaper to define than a class in a fresh class loader.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class HiddenClassDefiner
        implements ClassDefiner
{
    @Override
    public Class<?> define(Class<?> hostClass, byte[] bytecode)
            throws Exception
    {
        Lookup hostLookup = MethodHandles.privateLookupIn(hostClass, MethodHandles.lookup());
        return hostLookup.defineHiddenClass(bytecode, false, ClassOption.NESTMATE).lookupClass();
    }
}
