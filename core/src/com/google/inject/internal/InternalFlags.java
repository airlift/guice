/*
 * Copyright (C) 2013 Google Inc.
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

import java.util.Arrays;
import java.util.logging.Logger;

/** Contains flags for Guice. */
public final class InternalFlags {
  private static final Logger logger = Logger.getLogger(InternalFlags.class.getName());

  private static final IncludeStackTraceOption INCLUDE_STACK_TRACES =
      getSystemOption(
          "guice_include_stack_traces",
          IncludeStackTraceOption.ONLY_FOR_DECLARING_SOURCE);

  private static final CustomClassLoadingOption CUSTOM_CLASS_LOADING =
      getSystemOption("guice_custom_class_loading", CustomClassLoadingOption.BRIDGE);

  private static final NullableProvidesOption NULLABLE_PROVIDES =
      getSystemOption("guice_check_nullable_provides_params", NullableProvidesOption.ERROR);

  private static final BytecodeGenOption BYTECODE_GEN_OPTION =
      getSystemOption("guice_bytecode_gen_option", BytecodeGenOption.DISABLED);

  private static final ColorizeOption COLORIZE_OPTION =
      getSystemOption("guice_colorize_error_messages", ColorizeOption.OFF);

  private static final UseMethodHandlesOption USE_METHOD_HANDLES =
      getSystemOption("guice_use_method_handles", UseMethodHandlesOption.NO);

  private static final UseMethodHandlesOption USE_METHOD_HANDLES_FOR_MEMBER_INJECTION =
      getSystemOption("guice_use_method_handles_for_member_injection", UseMethodHandlesOption.YES);

  /**
   * The options for using `MethodHandles`.
   *
   * <p>YES builds MethodHandle chains at injector creation time and provisions through them instead
   * of reflection. It is a win for shallow graphs and member injection, but it does not scale: the
   * composed handle chains outgrow the JIT's inlining budget, so the cost grows with the size of the
   * graph being provisioned. Measured on a 300-binding graph, PRODUCTION-stage injector creation was
   * roughly twice as slow and provisioning the deepest type roughly four times as slow as the
   * reflective path. NO is therefore the default; turn it on only for small, shallow injectors.
   */
  public enum UseMethodHandlesOption {
    NO,
    YES,
  }

  /** The options for Guice stack trace collection. */
  public enum IncludeStackTraceOption {
    /** No stack trace collection */
    OFF,
    /** Minimum stack trace collection (Default) */
    ONLY_FOR_DECLARING_SOURCE,
  }

  /** The options for Guice custom class loading. */
  public enum CustomClassLoadingOption {
    /**
     * Define fast/enhanced types in the same class loader as their original type, never creates
     * class loaders. Uses {@link java.lang.invoke.MethodHandles.Lookup} to define the type in the
     * original type's runtime package.
     */
    OFF,

    /**
     * Define fast/enhanced types anonymously as hidden nest-mates, never creates class loaders.
     * This is faster than regular class loading and the resulting classes are easier to unload.
     *
     * <p>Note: with this option you cannot look up fast/enhanced types by name or mock/spy them.
     *
     * <p>Note: defining hidden classes needs full privilege access to the host, which is only
     * available when the host is in the same module as Guice. Hosts in other modules fall back to
     * being defined alongside their original type.
     */
    ANONYMOUS,

    /**
     * Attempt to define fast/enhanced types in the same class loader as their original type.
     * Otherwise creates a child class loader whose parent is the original class loader. (Default)
     */
    BRIDGE,

    /**
     * Define fast/enhanced types in a child class loader whose parent is the original class loader.
     *
     * <p>Note: with this option you cannot intercept package-private methods.
     */
    CHILD
  }

  /** Options for handling nullable parameters used in provides methods. */
  public enum NullableProvidesOption {
    /** Ignore null parameters to @Provides methods. */
    IGNORE,
    /** Warn if null parameters are passed to non-@Nullable parameters of provides methods. */
    WARN,
    /** Error if null parameters are passed to non-@Nullable parameters of provides parameters */
    ERROR
  }

  /**
   * Options for controlling whether Guice uses bytecode generation at runtime. When bytecode
   * generation is enabled, the following features will be enabled in Guice:
   *
   * <ul>
   *   <li>Runtime bytecode generation (instead of reflection) will be used when Guice need to
   *       invoke application code.
   *   <li>Method interception.
   * </ul>
   *
   * <p>Generating those classes costs about 22% of cold injector creation, measured on graphs of
   * 300 to 3000 bindings, and buys nothing measurable back: with member injection going through
   * MethodHandles rather than BytecodeGen, provisioning is the same either way. So this defaults to
   * DISABLED here, and the only thing given up is method interception.
   *
   * <p>Set {@code -Dguice_bytecode_gen_option=ENABLED} to get interception back.
   */
  public enum BytecodeGenOption {
    /**
     * Bytecode generation is disabled and using features that require it such as method
     * interception will throw errors at run time.
     */
    DISABLED,
    /** Bytecode generation is enabled. */
    ENABLED,
  }

  /** Options for enable or disable using ansi color in error messages. */
  public enum ColorizeOption {
    AUTO {
      @Override
      boolean enabled() {
        return System.console() != null && System.getenv("TERM") != null;
      }
    },
    ON {
      @Override
      boolean enabled() {
        return true;
      }
    },
    OFF {
      @Override
      boolean enabled() {
        return false;
      }
    };

    abstract boolean enabled();
  }

  public static IncludeStackTraceOption getIncludeStackTraceOption() {
    return INCLUDE_STACK_TRACES;
  }

  public static CustomClassLoadingOption getCustomClassLoadingOption() {
    return CUSTOM_CLASS_LOADING;
  }

  public static NullableProvidesOption getNullableProvidesOption() {
    return NULLABLE_PROVIDES;
  }

  public static boolean isBytecodeGenEnabled() {
    return BYTECODE_GEN_OPTION == BytecodeGenOption.ENABLED;
  }

  public static boolean enableColorizeErrorMessages() {
    return COLORIZE_OPTION.enabled();
  }

  /** Whether to construct instances through MethodHandle chains rather than reflection. */
  public static boolean getUseMethodHandlesOption() {
    return USE_METHOD_HANDLES
            == UseMethodHandlesOption.YES
        && isBytecodeGenEnabled();
  }

  /**
   * Whether to inject members through MethodHandles.
   *
   * <p>This is separate from {@link #getUseMethodHandlesOption} because the two paths behave very
   * differently. Handles do not scale for construction: the composed chains outgrow the JIT's
   * inlining budget, so cost grows with the size of the graph. Member injection composes a handle
   * per member instead, which stays small, and measures about twice as fast as reflection.
   */
  public static boolean getUseMethodHandlesForMemberInjectionOption() {
    // Deliberately not gated on bytecode generation. Member injection reaches its handles through
    // MethodHandles.Lookup.unreflect, which generates nothing; BytecodeGen is only a fallback for
    // when unreflect fails. Gating it here made disabling bytecode generation fall all the way back
    // to reflection, which cost more than the generation it saved.
    return USE_METHOD_HANDLES == UseMethodHandlesOption.YES
        || USE_METHOD_HANDLES_FOR_MEMBER_INJECTION == UseMethodHandlesOption.YES;
  }

  /**
   * Gets the system option indicated by the specified key.
   *
   * @param name of the system option
   * @param defaultValue if the option is not set
   * @return value of the option, defaultValue if not set
   */
  private static <T extends Enum<T>> T getSystemOption(final String name, T defaultValue) {
    Class<T> enumType = defaultValue.getDeclaringClass();
    String value = System.getProperty(name);
    try {
      return (value != null && value.length() > 0) ? Enum.valueOf(enumType, value) : defaultValue;
    } catch (IllegalArgumentException e) {
      logger.warning(
          value
              + " is not a valid flag value for "
              + name
              + ". "
              + " Values must be one of "
              + Arrays.asList(enumType.getEnumConstants()));
      return defaultValue;
    }
  }

  private InternalFlags() {}
}
