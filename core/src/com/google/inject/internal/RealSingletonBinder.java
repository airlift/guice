package com.google.inject.internal;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.SingletonBinder;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.binder.AnnotatedSingletonBindingBuilder;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.binder.LinkedSingletonBindingBuilder;
import com.google.inject.binder.ScopedBindingBuilder;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

import static com.google.inject.internal.Scoping.EAGER_SINGLETON;
import static com.google.inject.internal.Scoping.SINGLETON_INSTANCE;
import static java.util.Objects.requireNonNull;

/**
 * Creates bindings in a {@link Binder} and scopes each one as soon as it is created, so that
 * untargetted bindings are scoped too.
 */
public final class RealSingletonBinder
        implements SingletonBinder
{
    private final Binder binder;
    private final Scoping scoping;

    public RealSingletonBinder(Binder binder)
    {
        this(requireNonNull(binder, "binder").skipSources(RealSingletonBinder.class, LinkedBuilder.class, AnnotatedBuilder.class), SINGLETON_INSTANCE);
    }

    private RealSingletonBinder(Binder binder, Scoping scoping)
    {
        this.binder = binder;
        this.scoping = scoping;
    }

    @Override
    public SingletonBinder eagerly()
    {
        return (scoping == EAGER_SINGLETON) ? this : new RealSingletonBinder(binder, EAGER_SINGLETON);
    }

    @Override
    public <T> LinkedSingletonBindingBuilder<T> bind(Key<T> key)
    {
        return new LinkedBuilder<>(scoped(binder.bind(key)));
    }

    @Override
    public <T> AnnotatedSingletonBindingBuilder<T> bind(TypeLiteral<T> typeLiteral)
    {
        return new AnnotatedBuilder<>(scoped(binder.bind(typeLiteral)));
    }

    @Override
    public <T> AnnotatedSingletonBindingBuilder<T> bind(Class<T> type)
    {
        return new AnnotatedBuilder<>(scoped(binder.bind(type)));
    }

    private <B extends ScopedBindingBuilder> B scoped(B builder)
    {
        scoping.applyTo(builder);
        return builder;
    }

    private static class LinkedBuilder<T>
            implements LinkedSingletonBindingBuilder<T>
    {
        private final LinkedBindingBuilder<T> delegate;

        private LinkedBuilder(LinkedBindingBuilder<T> delegate)
        {
            this.delegate = requireNonNull(delegate, "delegate");
        }

        @Override
        public void to(Class<? extends T> implementation)
        {
            delegate.to(implementation);
        }

        @Override
        public void to(TypeLiteral<? extends T> implementation)
        {
            delegate.to(implementation);
        }

        @Override
        public void to(Key<? extends T> targetKey)
        {
            delegate.to(targetKey);
        }

        @Override
        public void toProvider(Provider<? extends @Nullable T> provider)
        {
            delegate.toProvider(provider);
        }

        @Override
        public void toProvider(jakarta.inject.Provider<? extends @Nullable T> provider)
        {
            delegate.toProvider(provider);
        }

        @Override
        public void toProvider(Class<? extends jakarta.inject.Provider<? extends @Nullable T>> providerType)
        {
            delegate.toProvider(providerType);
        }

        @Override
        public void toProvider(TypeLiteral<? extends jakarta.inject.Provider<? extends @Nullable T>> providerType)
        {
            delegate.toProvider(providerType);
        }

        @Override
        public void toProvider(Key<? extends jakarta.inject.Provider<? extends @Nullable T>> providerKey)
        {
            delegate.toProvider(providerKey);
        }

        @Override
        public <S extends T> void toConstructor(Constructor<S> constructor)
        {
            delegate.toConstructor(constructor);
        }

        @Override
        public <S extends T> void toConstructor(Constructor<S> constructor, TypeLiteral<? extends S> type)
        {
            delegate.toConstructor(constructor, type);
        }
    }

    private static final class AnnotatedBuilder<T>
            extends LinkedBuilder<T>
            implements AnnotatedSingletonBindingBuilder<T>
    {
        private final AnnotatedBindingBuilder<T> delegate;

        private AnnotatedBuilder(AnnotatedBindingBuilder<T> delegate)
        {
            super(delegate);
            this.delegate = requireNonNull(delegate, "delegate");
        }

        @Override
        public LinkedSingletonBindingBuilder<T> annotatedWith(Class<? extends Annotation> annotationType)
        {
            return new LinkedBuilder<>(delegate.annotatedWith(annotationType));
        }

        @Override
        public LinkedSingletonBindingBuilder<T> annotatedWith(Annotation annotation)
        {
            return new LinkedBuilder<>(delegate.annotatedWith(annotation));
        }
    }
}
