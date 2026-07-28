/*
 * Copyright (C) 2020 Google Inc.
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

import com.google.inject.internal.InternalFlags;
import com.google.inject.internal.InternalFlags.CustomClassLoadingOption;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;

/**
 * {@link ClassDefiner} that defines classes alongside their host using {@link Lookup#defineClass}.
 *
 * <p>The generated class is named in the host's package, so it lands in the host's runtime package
 * and class loader. That gives it access to the host's package-private members, lets it be looked
 * up by name, and avoids creating a class loader per generated type.
 *
 * <p>Hosts loaded by the bootstrap loader have no package we can define into, so those fall back to
 * hidden classes, as does {@link CustomClassLoadingOption#ANONYMOUS}.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class LookupClassDefiner implements ClassDefiner {

  private static final ClassDefiner HIDDEN_DEFINER = new HiddenClassDefiner();

  private static final boolean ALWAYS_DEFINE_ANONYMOUSLY =
      InternalFlags.getCustomClassLoadingOption() == CustomClassLoadingOption.ANONYMOUS;

  /** Returns true if it's possible to load by name proxies defined from the given host. */
  public static boolean canLoadProxyByName(Class<?> hostClass) {
    return !definesHiddenClass(hostClass);
  }

  /** Returns true if it's possible to downcast to proxies defined from the given host. */
  public static boolean canDowncastToProxy(Class<?> hostClass) {
    return true;
  }

  @Override
  public Class<?> define(Class<?> hostClass, byte[] bytecode) throws Exception {
    Lookup hostLookup = MethodHandles.privateLookupIn(hostClass, MethodHandles.lookup());
    // defineHiddenClass needs full privilege access, which a lookup teleported into another
    // module does not have; fall back to defining the class alongside its host.
    if (definesHiddenClass(hostClass) && hostLookup.hasFullPrivilegeAccess()) {
      return HIDDEN_DEFINER.define(hostClass, bytecode);
    }
    return hostLookup.defineClass(bytecode);
  }

  private static boolean definesHiddenClass(Class<?> hostClass) {
    return hostClass.getClassLoader() == null || ALWAYS_DEFINE_ANONYMOUSLY;
  }
}
