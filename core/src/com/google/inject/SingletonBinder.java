package com.google.inject;

import com.google.inject.binder.AnnotatedSingletonBindingBuilder;
import com.google.inject.binder.LinkedSingletonBindingBuilder;

/**
 * Creates bindings that are already singletons, returned by {@link Binder#asSingleton()}. Each
 * binding is created in the binder this came from, and is the same as one created there and then
 * scoped with {@code in(Scopes.SINGLETON)}, or with {@code asEagerSingleton()} for the binder
 * returned by {@link #eagerly()}:
 *
 * <pre>
 *     SingletonBinder singletons = binder.asSingleton();
 *     singletons.bind(ServiceImpl.class);
 *     singletons.bind(Service.class).annotatedWith(Red.class).to(RedService.class);
 *     singletons.eagerly().bind(Cache.class);</pre>
 *
 * <p>Since the scope is already set, the binding builders returned here have no scoping methods.
 * They have no {@code toInstance()} method either, because an instance binding is always an eager
 * singleton, whatever scope is requested. Bind instances with {@link Binder} instead.
 *
 * <p>{@link SingletonModule} is given one of these instead of a {@link Binder}, so all of its
 * bindings are singletons.
 */
public interface SingletonBinder
{
    /**
     * Returns a binder whose bindings are eager singletons, which the {@link Injector} creates along
     * with itself. Returns this binder if its bindings are already eager singletons.
     *
     * @return a binder that creates its bindings in the same binder as this one
     */
    SingletonBinder eagerly();

    /**
     * See the EDSL examples at {@link Binder}.
     */
    <T> LinkedSingletonBindingBuilder<T> bind(Key<T> key);

    /**
     * See the EDSL examples at {@link Binder}.
     */
    <T> AnnotatedSingletonBindingBuilder<T> bind(TypeLiteral<T> typeLiteral);

    /**
     * See the EDSL examples at {@link Binder}.
     */
    <T> AnnotatedSingletonBindingBuilder<T> bind(Class<T> type);
}
