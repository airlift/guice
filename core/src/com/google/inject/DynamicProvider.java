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

package com.google.inject;

/**
 * An object capable of providing instances of type {@code T} through a method whose signature is
 * chosen by the implementation rather than fixed by this interface.
 *
 * <p>Where {@link Provider} declares {@code T get()} and must therefore obtain its own dependencies
 * by other means, a {@code DynamicProvider} declares exactly one {@literal @}{@link Provides} method,
 * whose parameters are injected the same way as those of a module's {@literal @}Provides method.
 * Unlike a module's method, it belongs to an instance that the binding supplies, so one class can be
 * bound several times with different state, such as once per qualifier:
 *
 * <pre>{@code
 * class DataSourceProvider
 *         implements DynamicProvider<DataSource>
 * {
 *     private final String name;
 *
 *     DataSourceProvider(String name)
 *     {
 *         this.name = name;
 *     }
 *
 *     @Provides
 *     DataSource create(DataSourceFactory factory, @Named("pool.size") int poolSize)
 *     {
 *         return factory.create(name, poolSize);
 *     }
 * }
 *
 * for (String name : List.of("primary", "replica")) {
 *     bind(DataSource.class).annotatedWith(Names.named(name)).toProvider(new DataSourceProvider(name));
 * }
 * }</pre>
 *
 * <p>The provider method may have any name and any access modifier, and its return type must be
 * assignable to the bound type, or convertible to it unchecked, as {@code JsonCodec<?>} is to a
 * {@code JsonCodec<Foo>} key that the caller casts to {@code Key<JsonCodec<?>>}. Its parameters are resolved from the injector on every provision,
 * so missing bindings are reported when the injector is created, not when the first instance is
 * requested. It follows the rules for a module's {@literal @}Provides methods, so it may not be
 * overridden. The binding supplies the key and the scope, so the method may not have a binding
 * annotation or a scope annotation: use {@code annotatedWith()} and {@code in()} on the binding
 * instead.
 *
 * <p>An instance's {@link Inject} annotated fields and methods are injected like those of any other
 * provider instance bound with {@code toProvider}: when the injector is created, and in any case
 * before the provider method is first called. They may be used to give the provider its own
 * collaborators:
 *
 * <pre>{@code
 * class DataSourceProvider
 *         implements DynamicProvider<DataSource>
 * {
 *     @Inject
 *     private Metrics metrics;
 *
 *     @Provides
 *     DataSource create(DataSourceFactory factory) { ... }
 * }
 * }</pre>
 *
 * <p>An instance does not carry the type arguments of a generic class, so they are taken from the
 * bound type instead: an instance of {@code ConfigProvider<T> implements DynamicProvider<T>} bound to
 * {@code Foo} has its provider method and members resolved as those of a
 * {@code ConfigProvider<Foo>}. Such an instance should therefore be bound to exactly the type it
 * provides.
 *
 * <p>A {@code DynamicProvider} may also be bound by type, with {@code toProvider(Class)},
 * {@code toProvider(TypeLiteral)} or {@code toProvider(Key)}, or named by {@link ProvidedBy}. As
 * with any provider bound by type, the injector gets the instance from that type's binding on every
 * provision, so it may use constructor injection and is scoped by its own binding. The provider
 * method is resolved against the type arguments of that type, so a generic provider is bound with a
 * {@code TypeLiteral} or {@code Key} that supplies them. The provider method is found on the bound
 * type itself, so a subclass that the type is linked to should not override it.
 *
 * <p>Binding a {@code DynamicProvider} binds its provider method the way a module's
 * {@literal @}Provides method is bound, so {@link #get()} is never called. It throws
 * {@link UnsupportedOperationException}, so a {@code DynamicProvider} cannot be used by code that
 * calls {@code get()} itself. Implementing {@code get()} is an error, reported when the provider is
 * bound: an implementation that both provides from {@code get()} and declares a provider method
 * would silently use only the latter, so such a class should implement {@link Provider} instead.
 *
 * @param <T> the type of object this provides
 * @see com.google.inject.binder.LinkedBindingBuilder#toProvider(Provider)
 */
public interface DynamicProvider<T>
        extends Provider<T>
{
    @Override
    default T get()
    {
        throw new UnsupportedOperationException(
                getClass().getName() + " is a DynamicProvider; bind it with toProvider() or @ProvidedBy so"
                        + " its @Provides method is used");
    }
}
