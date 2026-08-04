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

/**
 * Contains flags for Guice.
 */
public final class InternalFlags
{
    private static final Logger logger = Logger.getLogger(InternalFlags.class.getName());

    private static final IncludeStackTraceOption INCLUDE_STACK_TRACES =
            getSystemOption("guice_include_stack_traces", IncludeStackTraceOption.OFF);

    private static final NullableProvidesOption NULLABLE_PROVIDES =
            getSystemOption("guice_check_nullable_provides_params", NullableProvidesOption.ERROR);

    private static final ColorizeOption COLORIZE_OPTION =
            getSystemOption("guice_colorize_error_messages", ColorizeOption.OFF);

    private static final UseMethodHandlesOption USE_METHOD_HANDLES =
            getSystemOption("guice_use_method_handles", UseMethodHandlesOption.NO);

    private static final UseMethodHandlesOption USE_METHOD_HANDLES_FOR_MEMBER_INJECTION =
            getSystemOption("guice_use_method_handles_for_member_injection", UseMethodHandlesOption.YES);

    /**
     * Whether member injection goes through MethodHandles (via Lookup.unreflect) or reflection.
     */
    public enum UseMethodHandlesOption
    {
        NO,
        YES,
    }

    /**
     * The options for Guice stack trace collection.
     *
     * <p>Despite the name, OFF does not mean errors lose their sources. Element sources fall back to
     * the module class that made the binding, and injection points always carry their own file and
     * line, which come from reflection rather than stack walking. What OFF drops is only the file and
     * line of the {@code bind()} statement itself: an error reads "at FooModule.configure(Unknown
     * Source)" instead of "at FooModule.configure(FooModule.java:42)".
     *
     * <p>Capturing that one line means walking the caller stack for every element of every module,
     * which is a measurable share of injector creation. OFF is therefore the default here;
     * set {@code -Dguice_include_stack_traces=ONLY_FOR_DECLARING_SOURCE} to get exact lines back
     * while debugging a wiring problem.
     */
    public enum IncludeStackTraceOption
    {
        /**
         * Attribute elements to their module, without walking the stack. (Default)
         */
        OFF,
        /**
         * Also capture the file and line of each binding statement.
         */
        ONLY_FOR_DECLARING_SOURCE,
    }

    /**
     * Options for handling nullable parameters used in provides methods.
     */
    public enum NullableProvidesOption
    {
        /**
         * Ignore null parameters to @Provides methods.
         */
        IGNORE,
        /**
         * Warn if null parameters are passed to non-@Nullable parameters of provides methods.
         */
        WARN,
        /**
         * Error if null parameters are passed to non-@Nullable parameters of provides parameters
         */
        ERROR,
    }

    /**
     * Options for enable or disable using ansi color in error messages.
     */
    public enum ColorizeOption
    {
        AUTO {
            @Override
            boolean enabled()
            {
                return System.console() != null && System.getenv("TERM") != null;
            }
        },
        ON {
            @Override
            boolean enabled()
            {
                return true;
            }
        },
        OFF {
            @Override
            boolean enabled()
            {
                return false;
            }
        };

        abstract boolean enabled();
    }

    public static IncludeStackTraceOption getIncludeStackTraceOption()
    {
        return INCLUDE_STACK_TRACES;
    }

    public static NullableProvidesOption getNullableProvidesOption()
    {
        return NULLABLE_PROVIDES;
    }

    public static boolean enableColorizeErrorMessages()
    {
        return COLORIZE_OPTION.enabled();
    }

    /**
     * Whether to inject members through MethodHandles.
     *
     * <p>Member injection composes one handle per member through Lookup.unreflect, which generates
     * nothing, stays within the JIT's inlining budget, and measures about twice as fast as
     * reflection.
     */
    public static boolean getUseMethodHandlesForMemberInjectionOption()
    {
        return USE_METHOD_HANDLES_FOR_MEMBER_INJECTION == UseMethodHandlesOption.YES;
    }

    /**
     * Gets the system option indicated by the specified key.
     *
     * @param name of the system option
     * @param defaultValue if the option is not set
     * @return value of the option, defaultValue if not set
     */
    private static <T extends Enum<T>> T getSystemOption(final String name, T defaultValue)
    {
        Class<T> enumType = defaultValue.getDeclaringClass();
        String value = System.getProperty(name);
        try {
            return (value != null && value.length() > 0) ? Enum.valueOf(enumType, value) : defaultValue;
        }
        catch (IllegalArgumentException e) {
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
