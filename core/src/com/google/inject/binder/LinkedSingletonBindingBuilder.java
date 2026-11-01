package com.google.inject.binder;

import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;

/**
 * A {@link LinkedBindingBuilder} for a binding whose scope is already set, so it has no scoping
 * methods. It has no {@code toInstance()} method either, since an instance binding is always an
 * eager singleton. See {@link com.google.inject.SingletonBinder}.
 */
public interface LinkedSingletonBindingBuilder<T>
{
    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void to(Class<? extends T> implementation);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void to(TypeLiteral<? extends T> implementation);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void to(Key<? extends T> targetKey);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     *
     * @see com.google.inject.Injector#injectMembers
     */
    void toProvider(Provider<? extends @Nullable T> provider);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     *
     * @see com.google.inject.Injector#injectMembers
     */
    void toProvider(jakarta.inject.Provider<? extends @Nullable T> provider);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void toProvider(Class<? extends jakarta.inject.Provider<? extends @Nullable T>> providerType);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void toProvider(TypeLiteral<? extends jakarta.inject.Provider<? extends @Nullable T>> providerType);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    void toProvider(Key<? extends jakarta.inject.Provider<? extends @Nullable T>> providerKey);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    <S extends T> void toConstructor(Constructor<S> constructor);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    <S extends T> void toConstructor(Constructor<S> constructor, TypeLiteral<? extends S> type);
}
