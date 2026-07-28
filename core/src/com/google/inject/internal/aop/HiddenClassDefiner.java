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

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.lang.reflect.Field;

/**
 * {@link ClassDefiner} that defines classes using {@link Lookup#defineHiddenClass}.
 *
 * <p>Note this deliberately uses the JDK's own trusted {@code IMPL_LOOKUP} rather than {@link
 * java.lang.invoke.MethodHandles#privateLookupIn}. A lookup obtained via {@code privateLookupIn} is
 * enough to define a hidden nest-mate of an ordinary host class, but it is not enough for {@link
 * UnsafeClassDefiner#accessDefineClass}, which forges a helper that calls the <em>protected</em>
 * {@code ClassLoader.defineClass}. Only the trusted lookup can do that, and without it Guice loses
 * the ability to define enhanced types in the host's own class loader.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
@SuppressWarnings("SunApi")
final class HiddenClassDefiner implements ClassDefiner {

  private static final sun.misc.Unsafe THE_UNSAFE;
  private static final Object TRUSTED_LOOKUP_BASE;
  private static final long TRUSTED_LOOKUP_OFFSET;

  /** True if this class err'd during initialization and should not be used. */
  static final boolean HAS_ERROR;

  static {
    sun.misc.Unsafe theUnsafe;
    Object trustedLookupBase;
    long trustedLookupOffset;
    try {
      theUnsafe = getUnsafe();
      Field trustedLookupField = Lookup.class.getDeclaredField("IMPL_LOOKUP");
      trustedLookupBase = theUnsafe.staticFieldBase(trustedLookupField);
      trustedLookupOffset = theUnsafe.staticFieldOffset(trustedLookupField);
    } catch (Throwable e) {
      // Allow the static initialization to complete without throwing an exception.
      theUnsafe = null;
      trustedLookupBase = null;
      trustedLookupOffset = 0;
    }

    THE_UNSAFE = theUnsafe;
    TRUSTED_LOOKUP_BASE = trustedLookupBase;
    TRUSTED_LOOKUP_OFFSET = trustedLookupOffset;
    HAS_ERROR = theUnsafe == null;
  }

  @Override
  public Class<?> define(Class<?> hostClass, byte[] bytecode) throws Exception {
    if (HAS_ERROR) {
      throw new IllegalStateException(
          "Should not be called. An earlier error occurred during HiddenClassDefiner static"
              + " initialization.");
    }

    Lookup trustedLookup =
        (Lookup) THE_UNSAFE.getObject(TRUSTED_LOOKUP_BASE, TRUSTED_LOOKUP_OFFSET);
    return trustedLookup
        .in(hostClass)
        .defineHiddenClass(bytecode, false, ClassOption.NESTMATE)
        .lookupClass();
  }

  private static sun.misc.Unsafe getUnsafe() throws ReflectiveOperationException {
    try {
      return sun.misc.Unsafe.getUnsafe();
    } catch (SecurityException unusedFallbackToReflection) {
      // fall through
    }
    Class<sun.misc.Unsafe> k = sun.misc.Unsafe.class;
    for (Field f : k.getDeclaredFields()) {
      f.setAccessible(true);
      Object x = f.get(null);
      if (k.isInstance(x)) {
        return k.cast(x);
      }
    }
    throw new NoSuchFieldError("the Unsafe");
  }
}
