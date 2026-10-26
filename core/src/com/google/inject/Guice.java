/*
 * Copyright (C) 2007 Google Inc.
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

package com.google.inject;

import com.google.common.collect.ImmutableMap;
import com.google.inject.internal.InternalInjectorCreator;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The entry point to the Guice framework. Creates {@link Injector}s from {@link Module}s.
 *
 * <p>Guice supports a model of development that draws clear boundaries between APIs,
 * Implementations of these APIs, Modules which configure these implementations, and finally
 * Applications which consist of a collection of Modules. It is the Application, which typically
 * defines your {@code main()} method, that bootstraps the Guice Injector using the {@code Guice}
 * class, as in this example:
 *
 * <pre>
 *     public class FooApplication {
 *       public static void main(String[] args) {
 *         Injector injector = Guice.createInjector(
 *             new ModuleA(),
 *             new ModuleB(),
 *             . . .
 *             new FooApplicationFlagsModule(args)
 *         );
 *
 *         // Now just bootstrap the application and you're done
 *         FooStarter starter = injector.getInstance(FooStarter.class);
 *         starter.runApplication();
 *       }
 *     }
 * </pre>
 */
public final class Guice
{
    private Guice() {}

    /**
     * Creates an injector for the given set of modules. This is equivalent to calling {@link
     * #createInjector(Stage, Module...)} with Stage.DEVELOPMENT.
     *
     * @throws CreationException if one or more errors occur during injector construction
     */
    public static Injector createInjector(Module... modules)
    {
        return createInjector(Arrays.asList(modules));
    }

    /**
     * Creates an injector for the given set of modules. This is equivalent to calling {@link
     * #createInjector(Stage, Iterable)} with Stage.DEVELOPMENT.
     *
     * @throws CreationException if one or more errors occur during injector creation
     */
    public static Injector createInjector(Iterable<? extends Module> modules)
    {
        return createInjector(Stage.DEVELOPMENT, modules);
    }

    /**
     * Creates an injector for the given set of modules, in a given development stage.
     *
     * @throws CreationException if one or more errors occur during injector creation.
     */
    public static Injector createInjector(Stage stage, Module... modules)
    {
        return createInjector(stage, Arrays.asList(modules));
    }

    /**
     * Creates an injector for the given set of modules, in a given development stage.
     *
     * @throws CreationException if one or more errors occur during injector construction
     */
    public static Injector createInjector(Stage stage, Iterable<? extends Module> modules)
    {
        return new InternalInjectorCreator().stage(stage).addModules(modules).build();
    }

    /**
     * Returns a builder for injectors whose binders return {@code value} from {@link
     * Binder#getAttribute} for {@code key}. Add more attributes with {@link Builder#withAttribute}.
     * Injectors created with the other methods of this class have no attributes.
     */
    public static Builder withAttribute(String key, Object value)
    {
        return new Builder(ImmutableMap.of()).withAttribute(key, value);
    }

    /**
     * Creates {@link Injector}s whose binders return this builder's attributes from {@link
     * Binder#getAttribute}. Builders are immutable and can be reused.
     */
    public static final class Builder
    {
        private final ImmutableMap<String, Object> attributes;

        private Builder(ImmutableMap<String, Object> attributes)
        {
            this.attributes = attributes;
        }

        /**
         * Returns a builder with this builder's attributes plus {@code value} for {@code key},
         * replacing any previous value for {@code key}.
         */
        public Builder withAttribute(String key, Object value)
        {
            requireNonNull(key, "key");
            requireNonNull(value, "value");
            Map<String, Object> newAttributes = new LinkedHashMap<>(attributes);
            newAttributes.put(key, value);
            return new Builder(ImmutableMap.copyOf(newAttributes));
        }

        /**
         * See {@link Guice#createInjector(Module...)}.
         */
        public Injector createInjector(Module... modules)
        {
            return createInjector(Arrays.asList(modules));
        }

        /**
         * See {@link Guice#createInjector(Iterable)}.
         */
        public Injector createInjector(Iterable<? extends Module> modules)
        {
            return createInjector(Stage.DEVELOPMENT, modules);
        }

        /**
         * See {@link Guice#createInjector(Stage, Module...)}.
         */
        public Injector createInjector(Stage stage, Module... modules)
        {
            return createInjector(stage, Arrays.asList(modules));
        }

        /**
         * See {@link Guice#createInjector(Stage, Iterable)}.
         */
        public Injector createInjector(Stage stage, Iterable<? extends Module> modules)
        {
            return new InternalInjectorCreator()
                    .stage(stage)
                    .attributes(attributes)
                    .addModules(modules)
                    .build();
        }
    }
}
