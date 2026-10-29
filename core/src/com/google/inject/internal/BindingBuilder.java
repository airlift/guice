/*
 * Copyright (C) 2008 Google Inc.
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

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeResolver;
import com.google.common.reflect.TypeToken;
import com.google.inject.Binder;
import com.google.inject.ConfigurationException;
import com.google.inject.DynamicProvider;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.binder.ScopedBindingBuilder;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.Element;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import com.google.inject.util.Types;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Bind a non-constant key.
 */
public final class BindingBuilder<T>
        extends AbstractBindingBuilder<T>
        implements AnnotatedBindingBuilder<T>
{
    public BindingBuilder(Binder binder, List<Element> elements, Object source, Key<T> key)
    {
        super(binder, elements, source, key);
    }

    @Override
    public BindingBuilder<T> annotatedWith(Class<? extends Annotation> annotationType)
    {
        annotatedWithInternal(annotationType);
        return this;
    }

    @Override
    public BindingBuilder<T> annotatedWith(Annotation annotation)
    {
        annotatedWithInternal(annotation);
        return this;
    }

    @Override
    public BindingBuilder<T> to(Class<? extends T> implementation)
    {
        return to(Key.get(implementation));
    }

    @Override
    public BindingBuilder<T> to(TypeLiteral<? extends T> implementation)
    {
        return to(Key.get(implementation));
    }

    @Override
    public BindingBuilder<T> to(Key<? extends T> linkedKey)
    {
        requireNonNull(linkedKey, "linkedKey");
        checkNotTargetted();
        BindingImpl<T> base = getBinding();
        setBinding(
                new LinkedBindingImpl<T>(base.getSource(), base.getKey(), base.getScoping(), linkedKey));
        return this;
    }

    @Override
    public void toInstance(T instance)
    {
        checkNotTargetted();

        // lookup the injection points, adding any errors to the binder's errors list
        Set<InjectionPoint> injectionPoints;
        if (instance != null) {
            try {
                injectionPoints = InjectionPoint.forInstanceMethodsAndFields(instance.getClass());
            }
            catch (ConfigurationException e) {
                copyErrorsToBinder(e);
                injectionPoints = e.getPartialValue();
            }
        }
        else {
            binder.addError(BINDING_TO_NULL);
            injectionPoints = ImmutableSet.of();
        }

        BindingImpl<T> base = getBinding();
        setBinding(
                new InstanceBindingImpl<T>(
                        base.getSource(), base.getKey(), Scoping.EAGER_SINGLETON, injectionPoints, instance));
    }

    @Override
    public BindingBuilder<T> toProvider(Provider<? extends T> provider)
    {
        return toProvider((jakarta.inject.Provider<? extends T>) provider);
    }

    @Override
    public BindingBuilder<T> toProvider(jakarta.inject.Provider<? extends T> provider)
    {
        requireNonNull(provider, "provider");
        checkNotTargetted();

        if (provider instanceof DynamicProvider<?> dynamicProvider) {
            return toDynamicProviderInstance(dynamicProvider);
        }
        // A DynamicProvider instance binding that is applied again, as Modules.override does,
        // supplies the ProviderMethod it was bound to.
        if (provider instanceof ProviderMethod<?> providerMethod
                && providerMethod.getInstance() instanceof DynamicProvider<?> dynamicProvider) {
            return toDynamicProviderInstance(dynamicProvider);
        }

        // lookup the injection points, adding any errors to the binder's errors list
        Set<InjectionPoint> injectionPoints;
        try {
            injectionPoints = InjectionPoint.forInstanceMethodsAndFields(provider.getClass());
        }
        catch (ConfigurationException e) {
            copyErrorsToBinder(e);
            injectionPoints = e.getPartialValue();
        }

        BindingImpl<T> base = getBinding();
        setBinding(
                new ProviderInstanceBindingImpl<T>(
                        base.getSource(), base.getKey(), base.getScoping(), injectionPoints, provider));
        return this;
    }

    @Override
    public BindingBuilder<T> toProvider(
            Class<? extends jakarta.inject.Provider<? extends T>> providerType)
    {
        return toProvider(Key.get(providerType));
    }

    @Override
    public BindingBuilder<T> toProvider(
            TypeLiteral<? extends jakarta.inject.Provider<? extends T>> providerType)
    {
        return toProvider(Key.get(providerType));
    }

    @Override
    public BindingBuilder<T> toProvider(
            Key<? extends jakarta.inject.Provider<? extends T>> providerKey)
    {
        requireNonNull(providerKey, "providerKey");
        checkNotTargetted();

        if (DynamicProvider.class.isAssignableFrom(providerKey.getTypeLiteral().getRawType())) {
            return toDynamicProviderType(providerKey);
        }

        BindingImpl<T> base = getBinding();
        setBinding(
                new LinkedProviderBindingImpl<T>(
                        base.getSource(), base.getKey(), base.getScoping(), providerKey));
        return this;
    }

    /**
     * Binds {@code dynamicProvider}'s {@literal @}Provides method the way a module's is bound, except
     * that the key and scope come from this binding rather than from the method.
     */
    private BindingBuilder<T> toDynamicProviderInstance(DynamicProvider<?> dynamicProvider)
    {
        TypeLiteral<?> providerType =
                resolveAgainstBoundType(dynamicProvider.getClass(), getBinding().getKey().getTypeLiteral());
        ProviderMethod<?> providerMethod = findProviderMethod(providerType);
        if (providerMethod == null) {
            return toPlaceholder();
        }

        Set<InjectionPoint> injectionPoints;
        try {
            injectionPoints = InjectionPoint.forInstanceMethodsAndFields(providerType);
        }
        catch (ConfigurationException e) {
            copyErrorsToBinder(e);
            injectionPoints = e.getPartialValue();
        }

        // The binding carries the DynamicProvider's injection points, as it would a provider
        // instance's, so that BindingProcessor has its members injected before its first use.
        BindingImpl<T> base = getBinding();
        setBinding(
                new ProviderInstanceBindingImpl<T>(
                        base.getSource(),
                        base.getKey(),
                        base.getScoping(),
                        injectionPoints,
                        ProviderMethod.create(
                                base.getKey(),
                                providerMethod.getMethod(),
                                dynamicProvider,
                                ImmutableSet.copyOf(providerMethod.getDependencies()),
                                null,
                                true,
                                providerMethod.getAnnotation())));
        return this;
    }

    /**
     * Returns {@code providerClass}, a DynamicProvider class, with the type arguments that an instance
     * does not carry taken from {@code boundType}: a {@code ConfigProvider<T> implements
     * DynamicProvider<T>} bound to {@code Foo} is a {@code ConfigProvider<Foo>}. A type parameter that
     * the bound type does not determine is left unresolved, and so is reported if the provider method
     * or a member uses it.
     */
    static TypeLiteral<?> resolveAgainstBoundType(Class<?> providerClass, TypeLiteral<?> boundType)
    {
        TypeVariable<?>[] typeParameters = providerClass.getTypeParameters();
        if (typeParameters.length == 0) {
            return TypeLiteral.get(providerClass);
        }

        // DynamicProvider<X> as providerClass declares it, with X in terms of its type parameters
        Type declared = TypeLiteral.get(providerClass).getSupertype(DynamicProvider.class).getType();
        Type bound = Types.newParameterizedType(DynamicProvider.class, boundType.getType());
        TypeResolver resolver;
        try {
            resolver = new TypeResolver().where(declared, bound);
        }
        catch (IllegalArgumentException e) {
            // The bound type has a different shape, such as a supertype of List<E> for DynamicProvider<List<E>>
            return TypeLiteral.get(providerClass);
        }
        Type[] typeArguments = Arrays.stream(typeParameters).map(resolver::resolveType).toArray(Type[]::new);
        return TypeLiteral.get(Types.newParameterizedTypeWithOwner(providerClass.getEnclosingClass(), providerClass, typeArguments));
    }

    /**
     * Binds the {@literal @}Provides method of the DynamicProvider type that {@code providerKey}
     * names, as {@link #toDynamicProviderInstance} binds an instance's, except that the instance to
     * call it on is injected through {@code providerKey} on every provision, the way a provider bound
     * by type is.
     */
    private BindingBuilder<T> toDynamicProviderType(Key<?> providerKey)
    {
        ProviderMethod<?> providerMethod = findProviderMethod(providerKey.getTypeLiteral());
        if (providerMethod == null) {
            return toPlaceholder();
        }

        // The instance is the provider method's first dependency
        ImmutableSet<Dependency<?>> dependencies =
                ImmutableSet.<Dependency<?>>builder()
                        .add(Dependency.get(providerKey))
                        .addAll(providerMethod.getDependencies())
                        .build();
        BindingImpl<T> base = getBinding();
        setBinding(
                new ProviderInstanceBindingImpl<T>(
                        base.getSource(),
                        base.getKey(),
                        base.getScoping(),
                        ImmutableSet.of(),
                        ProviderMethod.create(
                                base.getKey(),
                                providerMethod.getMethod(),
                                null,
                                dependencies,
                                null,
                                true,
                                providerMethod.getAnnotation())));
        return this;
    }

    /**
     * Returns the single {@literal @}Provides method of {@code providerType}, a DynamicProvider type,
     * with its types resolved against the type arguments of {@code providerType}. Returns null after
     * reporting why {@code providerType} cannot provide this binding's type.
     */
    private ProviderMethod<?> findProviderMethod(TypeLiteral<?> providerType)
    {
        Class<?> rawType = providerType.getRawType();
        if (implementsGet(rawType)) {
            binder.addError(
                    "%s implements get(), which is never called for a DynamicProvider; implement Provider"
                            + " instead, or remove get() so that the @Provides method provides the instance",
                    rawType);
            return null;
        }

        List<ProviderMethod<?>> providerMethods;
        try {
            providerMethods = ProviderMethodsModule.forType(providerType).getProviderMethods(binder);
        }
        catch (ConfigurationException e) {
            // A provider method whose types cannot be keys, such as a type parameter that providerType
            // does not resolve
            copyErrorsToBinder(e);
            return null;
        }
        if (providerMethods.size() != 1) {
            binder.addError(
                    "%s must have exactly one @Provides method, found %s%s",
                    rawType,
                    providerMethods.size(),
                    providerMethods.isEmpty()
                            ? ""
                            : ": " + providerMethods.stream()
                                     .map(providerMethod -> providerMethod.getMethod().toString())
                                     .collect(joining(", ")));
            return null;
        }

        ProviderMethod<?> providerMethod = providerMethods.getFirst();
        Method method = providerMethod.getMethod();
        if (providerMethod.getKey().getAnnotationType() != null) {
            binder.addError(
                    "@Provides method %s of %s has a binding annotation, which a DynamicProvider would"
                            + " ignore; use annotatedWith() on the binding instead",
                    method,
                    rawType);
            return null;
        }
        if (providerMethod.getScopeAnnotation() != null) {
            binder.addError(
                    "@Provides method %s of %s has a scope annotation, which a DynamicProvider would"
                            + " ignore; use in() on the binding instead",
                    method,
                    rawType);
            return null;
        }
        TypeLiteral<T> boundType = getBinding().getKey().getTypeLiteral();
        // Resolved against providerType, so type arguments are compared, not just classes
        TypeLiteral<?> returnType = providerType.getReturnType(method);
        if (!canProvide(TypeToken.of(returnType.getType()).wrap(), TypeToken.of(boundType.getType()))) {
            binder.addError(
                    "@Provides method %s of %s returns %s, which is not assignable to %s",
                    method,
                    rawType,
                    returnType,
                    boundType);
            return null;
        }
        return providerMethod;
    }

    /**
     * Targets a binding whose provider was rejected, so that the injector does not also report the
     * bound type as having no injectable constructor. It is never provisioned, since the injector
     * fails on the error already reported.
     */
    private BindingBuilder<T> toPlaceholder()
    {
        BindingImpl<T> base = getBinding();
        setBinding(
                new ProviderInstanceBindingImpl<T>(
                        base.getSource(), base.getKey(), base.getScoping(), ImmutableSet.of(), Providers.of(null)));
        return this;
    }

    /**
     * Whether a provider method that returns {@code returnType} can provide {@code boundType}: either
     * it is assignable, or it is an unchecked conversion that its type arguments could make true, as
     * for {@code JsonCodec<?>} bound as {@code JsonCodec<Foo>}, which a caller can only bind by casting
     * the key. A conversion that no type arguments make true, such as from {@code List<String>} to
     * {@code List<Integer>}, is rejected.
     */
    private static boolean canProvide(TypeToken<?> returnType, TypeToken<?> boundType)
    {
        if (boundType.isSupertypeOf(returnType)) {
            return true;
        }
        Class<?> rawReturnType = returnType.getRawType();
        if (!boundType.getRawType().isAssignableFrom(rawReturnType)) {
            return false;
        }
        try {
            // The bound type as a rawReturnType, such as ArrayList<String> for List<String>
            return returnType.isSupertypeOf(boundType.getSubtype(rawReturnType));
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether {@code providerType} overrides {@link DynamicProvider#get()}, which is never called.
     * Reflection finds the most specific public {@code get()}, so this covers an override inherited
     * from a superclass or from an interface that extends {@code DynamicProvider}.
     */
    private static boolean implementsGet(Class<?> providerType)
    {
        try {
            return providerType.getMethod("get").getDeclaringClass() != DynamicProvider.class;
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public <S extends T> ScopedBindingBuilder toConstructor(Constructor<S> constructor)
    {
        return toConstructor(constructor, TypeLiteral.get(constructor.getDeclaringClass()));
    }

    @Override
    public <S extends T> ScopedBindingBuilder toConstructor(
            Constructor<S> constructor,
            TypeLiteral<? extends S> type)
    {
        requireNonNull(constructor, "constructor");
        requireNonNull(type, "type");
        checkNotTargetted();

        BindingImpl<T> base = getBinding();

        Set<InjectionPoint> injectionPoints;
        try {
            injectionPoints = InjectionPoint.forInstanceMethodsAndFields(type);
        }
        catch (ConfigurationException e) {
            copyErrorsToBinder(e);
            injectionPoints = e.getPartialValue();
        }

        try {
            InjectionPoint constructorPoint = InjectionPoint.forConstructor(constructor, type);
            setBinding(
                    new ConstructorBindingImpl<T>(
                            base.getKey(),
                            base.getSource(),
                            base.getScoping(),
                            constructorPoint,
                            injectionPoints));
        }
        catch (ConfigurationException e) {
            copyErrorsToBinder(e);
        }

        return this;
    }

    @Override
    public String toString()
    {
        return "BindingBuilder<" + getBinding().getKey().getTypeLiteral() + ">";
    }

    private void copyErrorsToBinder(ConfigurationException e)
    {
        for (Message message : e.getErrorMessages()) {
            binder.addError(message);
        }
    }
}
