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

/**
 * Entry-point for defining dynamically generated classes.
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
public final class ClassDefining {
  private ClassDefining() {}

  // initialization-on-demand...
  private static class ClassDefinerHolder {
    static final ClassDefiner INSTANCE = bindClassDefiner();
    static final boolean IS_LOOKUP = INSTANCE instanceof LookupClassDefiner;
  }

  /** Defines a new class relative to the host. */
  public static Class<?> define(Class<?> hostClass, byte[] bytecode) throws Exception {
    return ClassDefinerHolder.INSTANCE.define(hostClass, bytecode);
  }

  /** Returns true if the current class definer allows access to package-private members. */
  public static boolean hasPackageAccess() {
    return ClassDefinerHolder.IS_LOOKUP;
  }

  /** Returns true if it's possible to load by name proxies defined from the given host. */
  public static boolean canLoadProxyByName(Class<?> hostClass) {
    return !ClassDefinerHolder.IS_LOOKUP || LookupClassDefiner.canLoadProxyByName(hostClass);
  }

  /** Returns true if it's possible to downcast to proxies defined from the given host. */
  public static boolean canDowncastToProxy(Class<?> hostClass) {
    return !ClassDefinerHolder.IS_LOOKUP || LookupClassDefiner.canDowncastToProxy(hostClass);
  }

  /** Binds the preferred {@link ClassDefiner} instance. */
  static ClassDefiner bindClassDefiner() {
    // ANONYMOUS keeps the lookup definer but changes how it defines classes
    CustomClassLoadingOption loadingOption = InternalFlags.getCustomClassLoadingOption();
    if (loadingOption == CustomClassLoadingOption.CHILD) {
      return new ChildClassDefiner(); // override default choice
    }
    return new LookupClassDefiner();
  }
}
