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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.spi.Message;
import com.google.inject.spi.ModuleAnnotatedMethodScanner;
import com.google.inject.util.Modules;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Creates bindings to methods annotated with {@literal @}{@link Provides}. Use the scope and
 * binding annotations on the provider method to configure the binding.
 *
 * @author crazybob@google.com (Bob Lee)
 * @author jessewilson@google.com (Jesse Wilson)
 */
public final class ProviderMethodsModule
        implements Module
{
    private final Object delegate;
    private final TypeLiteral<?> typeLiteral;
    private final boolean skipFastClassGeneration;
    private final ModuleAnnotatedMethodScanner scanner;

    private ProviderMethodsModule(
            Object delegate,
            boolean skipFastClassGeneration,
            ModuleAnnotatedMethodScanner scanner)
    {
        this.delegate = requireNonNull(delegate, "delegate");
        this.typeLiteral = TypeLiteral.get(getDelegateModuleClass());
        this.skipFastClassGeneration = skipFastClassGeneration;
        this.scanner = scanner;
    }

    /**
     * Returns a module which creates bindings for provider methods from the given module.
     */
    public static Module forModule(Module module)
    {
        return forObject(module, false, ProvidesMethodScanner.INSTANCE);
    }

    /**
     * Returns a module which creates bindings methods in the module that match the scanner.
     */
    public static Module forModule(Object module, ModuleAnnotatedMethodScanner scanner)
    {
        return forObject(module, false, scanner);
    }

    /**
     * Returns a module which creates bindings for provider methods from the given object. This is
     * useful notably for <a href="http://code.google.com/p/google-gin/">GIN</a>
     *
     * <p>This will skip bytecode generation for provider methods, since it is assumed that callers
     * are only interested in Module metadata.
     */
    public static Module forObject(Object object)
    {
        return forObject(object, true, ProvidesMethodScanner.INSTANCE);
    }

    private static Module forObject(
            Object object,
            boolean skipFastClassGeneration,
            ModuleAnnotatedMethodScanner scanner)
    {
        // avoid infinite recursion, since installing a module always installs itself
        if (object instanceof ProviderMethodsModule) {
            return Modules.EMPTY_MODULE;
        }

        return new ProviderMethodsModule(object, skipFastClassGeneration, scanner);
    }

    public Class<?> getDelegateModuleClass()
    {
        return isStaticModule() ? (Class<?>) delegate : delegate.getClass();
    }

    private boolean isStaticModule()
    {
        return delegate instanceof Class;
    }

    @Override
    public void configure(Binder binder)
    {
        for (ProviderMethod<?> providerMethod : getProviderMethods(binder)) {
            providerMethod.configure(binder);
        }
    }

    public List<ProviderMethod<?>> getProviderMethods(Binder binder)
    {
        List<ProviderMethod<?>> result = null;
        List<MethodAndAnnotation> methodsAndAnnotations = null;
        // The highest class in the type hierarchy that contained a provider method definition.
        Class<?> superMostClass = getDelegateModuleClass();
        for (Class<?> c = superMostClass; c != Object.class && c != null; c = c.getSuperclass()) {
            for (MethodAndAnnotation methodAndAnnotation :
                    getDeclaredProviderAnnotatedMethods(c, binder)) {
                Annotation annotation = methodAndAnnotation.annotation;
                Method method = methodAndAnnotation.method;
                if (isStaticModule()
                        && !Modifier.isStatic(method.getModifiers())
                        && !Modifier.isAbstract(method.getModifiers())) {
                    binder.addError(
                            "%s is an instance method, but a class literal was passed. Make this method"
                                    + " static or pass an instance of the module instead.",
                            method);
                    continue;
                }
                if (result == null) {
                    result = new ArrayList<>();
                    methodsAndAnnotations = new ArrayList<>();
                }

                ProviderMethod<Object> providerMethod = createProviderMethod(binder, method, annotation);
                if (providerMethod != null) {
                    result.add(providerMethod);
                }
                methodsAndAnnotations.add(methodAndAnnotation);
                superMostClass = c;
            }
        }
        if (result == null) {
            // We didn't find anything
            return ImmutableList.of();
        }
        // We have found some provider methods, now we need to check if any were overridden.
        // We do this as a separate pass to avoid calculating all the signatures when there are no
        // provides methods, or when all provides methods are defined in a single class.
        Multimap<Signature, Method> methodsBySignature = null;
        // We can stop scanning when we see superMostClass, since no superclass method can override
        // a method in a subclass.  Corrollary, if superMostClass == getDelegateModuleClass(), there can
        // be no overrides of a provides method.
        for (Class<?> c = getDelegateModuleClass(); c != superMostClass; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (((method.getModifiers() & (Modifier.PRIVATE | Modifier.STATIC)) == 0)
                        && !method.isBridge()
                        && !method.isSynthetic()) {
                    if (methodsBySignature == null) {
                        methodsBySignature = HashMultimap.create();
                    }
                    methodsBySignature.put(new Signature(typeLiteral, method), method);
                }
            }
        }
        if (methodsBySignature != null) {
            // we have found all the signatures and now need to identify if any were overridden
            // In the worst case this will have O(n^2) in the number of @Provides methods, but that is
            // only assuming that every method is an override, in general it should be very quick.
            for (MethodAndAnnotation methodAndAnnotation : methodsAndAnnotations) {
                Method method = methodAndAnnotation.method;
                Annotation annotation = methodAndAnnotation.annotation;

                for (Method matchingSignature :
                        methodsBySignature.get(new Signature(typeLiteral, method))) {
                    // matching signature is in the same class or a super class, therefore method cannot be
                    // overridding it.
                    if (matchingSignature.getDeclaringClass().isAssignableFrom(method.getDeclaringClass())) {
                        continue;
                    }
                    // now we know matching signature is in a subtype of method.getDeclaringClass()
                    if (overrides(matchingSignature, method)) {
                        String annotationString =
                                annotation.annotationType() == Provides.class
                                        ? "@Provides"
                                        : "@" + annotation.annotationType().getCanonicalName();
                        binder.addError(
                                "Overriding "
                                        + annotationString
                                        + " methods is not allowed."
                                        + "\n\t"
                                        + annotationString
                                        + " method: %s\n\toverridden by: %s",
                                method,
                                matchingSignature);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static final Comparator<MethodAndAnnotation> METHOD_AND_ANNOTATION_COMPARATOR =
            Comparator.comparing(
                    methodAndAnnotation -> methodAndAnnotation.method, DeclaredMembers.METHOD_COMPARATOR);

    private List<MethodAndAnnotation> getDeclaredProviderAnnotatedMethods(Class<?> c, Binder binder)
    {
        // The default scanner's result depends only on the class, and modules are re-scanned on
        // every injector creation, cloning Method arrays and annotation arrays each time. Cache per
        // class; duplicate-claim errors are part of the cached result and are re-reported to every
        // binder so repeated creations fail the same way. Custom scanners keep the live path: their
        // annotation sets are not known to be stable.
        if (scanner == ProvidesMethodScanner.INSTANCE) {
            DefaultScan scan = DEFAULT_SCAN.get(c);
            for (Method method : scan.duplicateClaims) {
                addDuplicateClaimError(binder, method);
            }
            return scan.methods;
        }
        List<MethodAndAnnotation> result = new ArrayList<>();
        for (Method method : c.getDeclaredMethods()) {
            Annotation annotation = getAnnotation(binder, method);
            if (annotation != null) {
                result.add(new MethodAndAnnotation(method, annotation));
            }
        }
        result.sort(METHOD_AND_ANNOTATION_COMPARATOR);
        return result;
    }

    /**
     * Class-stable derivation for one default-scanner provider method: everything
     * createProviderMethod computes before the scanner's prepareMethod runs. prepareMethod itself
     * must stay live because multibinding scanners mint unique keys per call. Keyed by the concrete
     * module class because generic return and parameter types resolve against it, not against the
     * declaring class.
     */
    private static final class ProviderMethodMeta
    {
        final InjectionPoint injectionPoint;
        final Key<?> baseKey;
        final Class<? extends Annotation> scopeAnnotation;
        final ImmutableSet<Dependency<?>> dependencies;
        final ImmutableList<Message> messages;

        ProviderMethodMeta(
                InjectionPoint injectionPoint,
                Key<?> baseKey,
                Class<? extends Annotation> scopeAnnotation,
                ImmutableSet<Dependency<?>> dependencies,
                ImmutableList<Message> messages)
        {
            this.injectionPoint = injectionPoint;
            this.baseKey = baseKey;
            this.scopeAnnotation = scopeAnnotation;
            this.dependencies = dependencies;
            this.messages = messages;
        }
    }

    private static final ClassValue<ImmutableMap<Method, ProviderMethodMeta>> PROVIDER_METHOD_METAS =
            new ClassValue<>()
            {
                @Override
                protected ImmutableMap<Method, ProviderMethodMeta> computeValue(Class<?> concreteClass)
                {
                    TypeLiteral<?> typeLiteral = TypeLiteral.get(concreteClass);
                    ImmutableMap.Builder<Method, ProviderMethodMeta> metas = ImmutableMap.builder();
                    for (Class<?> c = concreteClass; c != Object.class && c != null; c = c.getSuperclass()) {
                        for (MethodAndAnnotation methodAndAnnotation : DEFAULT_SCAN.get(c).methods) {
                            Method method = methodAndAnnotation.method;
                            Errors errors = new Errors(method);
                            InjectionPoint point = InjectionPoint.forMethod(method, typeLiteral);
                            TypeLiteral<?> returnType = typeLiteral.getReturnType(method);
                            Annotation bindingAnnotation =
                                    Annotations.findBindingAnnotation(errors, method, method.getAnnotations());
                            Key<?> baseKey =
                                    bindingAnnotation == null
                                            ? Key.get(returnType)
                                            : Key.get(returnType, bindingAnnotation);
                            Class<? extends Annotation> scopeAnnotation =
                                    Annotations.findScopeAnnotation(errors, method.getAnnotations());
                            metas.put(
                                    method,
                                    new ProviderMethodMeta(
                                            point,
                                            baseKey,
                                            scopeAnnotation,
                                            ImmutableSet.copyOf(point.getDependencies()),
                                            ImmutableList.copyOf(errors.getMessages())));
                        }
                    }
                    return metas.buildOrThrow();
                }
            };

    private void addDuplicateClaimError(Binder binder, Method method)
    {
        binder.addError(
                "More than one annotation claimed by %s on method %s."
                        + " Methods can only have one annotation claimed per scanner.",
                scanner,
                method);
    }

    private static final class DefaultScan
    {
        final ImmutableList<MethodAndAnnotation> methods;
        final ImmutableList<Method> duplicateClaims;

        DefaultScan(ImmutableList<MethodAndAnnotation> methods, ImmutableList<Method> duplicateClaims)
        {
            this.methods = methods;
            this.duplicateClaims = duplicateClaims;
        }
    }

    private static final ClassValue<DefaultScan> DEFAULT_SCAN =
            new ClassValue<>()
            {
                @Override
                protected DefaultScan computeValue(Class<?> c)
                {
                    List<MethodAndAnnotation> methods = new ArrayList<>();
                    ImmutableList.Builder<Method> duplicates = ImmutableList.builder();
                    for (Method method : c.getDeclaredMethods()) {
                        if (method.isBridge() || method.isSynthetic()) {
                            continue;
                        }
                        Annotation annotation = null;
                        boolean duplicate = false;
                        for (Class<? extends Annotation> annotationClass :
                                ProvidesMethodScanner.INSTANCE.annotationClasses()) {
                            Annotation found = method.getAnnotation(annotationClass);
                            if (found != null) {
                                if (annotation != null) {
                                    duplicates.add(method);
                                    duplicate = true;
                                    break;
                                }
                                annotation = found;
                            }
                        }
                        if (!duplicate && annotation != null) {
                            methods.add(new MethodAndAnnotation(method, annotation));
                        }
                    }
                    methods.sort(METHOD_AND_ANNOTATION_COMPARATOR);
                    return new DefaultScan(ImmutableList.copyOf(methods), duplicates.build());
                }
            };

    private static class MethodAndAnnotation
    {
        final Method method;
        final Annotation annotation;

        MethodAndAnnotation(Method method, Annotation annotation)
        {
            this.method = method;
            this.annotation = annotation;
        }
    }

    /**
     * Returns the annotation that is claimed by the scanner, or null if there is none.
     */
    private Annotation getAnnotation(Binder binder, Method method)
    {
        if (method.isBridge() || method.isSynthetic()) {
            return null;
        }
        Annotation annotation = null;
        for (Class<? extends Annotation> annotationClass : scanner.annotationClasses()) {
            Annotation foundAnnotation = method.getAnnotation(annotationClass);
            if (foundAnnotation != null) {
                if (annotation != null) {
                    binder.addError(
                            "More than one annotation claimed by %s on method %s."
                                    + " Methods can only have one annotation claimed per scanner.",
                            scanner,
                            method);
                    return null;
                }
                annotation = foundAnnotation;
            }
        }
        return annotation;
    }

    private static final class Signature
    {
        final Class<?>[] parameters;
        final String name;
        final int hashCode;

        Signature(TypeLiteral<?> typeLiteral, Method method)
        {
            this.name = method.getName();
            // We need to 'resolve' the parameters against the actual class type in case this method uses
            // type parameters.  This is so we can detect overrides of generic superclass methods where
            // the subclass specifies the type parameter.  javac implements these kinds of overrides via
            // bridge methods, but we don't want to give errors on bridge methods (but rather the target
            // of the bridge).
            List<TypeLiteral<?>> resolvedParameterTypes = typeLiteral.getParameterTypes(method);
            this.parameters = new Class<?>[resolvedParameterTypes.size()];
            int i = 0;
            for (TypeLiteral<?> type : resolvedParameterTypes) {
                parameters[i] = type.getRawType();
            }
            this.hashCode = name.hashCode() + 31 * Arrays.hashCode(parameters);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof Signature other) {
                return other.name.equals(name) && Arrays.equals(parameters, other.parameters);
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return hashCode;
        }
    }

    /**
     * Returns true if a overrides b, assumes that the signatures match
     */
    private static boolean overrides(Method a, Method b)
    {
        // See JLS section 8.4.8.1
        int modifiers = b.getModifiers();
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        // b must be package-private
        return a.getDeclaringClass().getPackage().equals(b.getDeclaringClass().getPackage());
    }

    private <T> ProviderMethod<T> createProviderMethod(
            Binder binder,
            Method method,
            Annotation annotation)
    {
        binder = binder.withSource(method);

        InjectionPoint point;
        Key<T> key;
        Class<? extends Annotation> scopeAnnotation;
        ImmutableSet<Dependency<?>> dependencies;
        ImmutableList<Message> messages;
        if (scanner == ProvidesMethodScanner.INSTANCE) {
            // Everything before prepareMethod is class-stable and shared across creations.
            ProviderMethodMeta meta = PROVIDER_METHOD_METAS.get(getDelegateModuleClass()).get(method);
            point = meta.injectionPoint;
            @SuppressWarnings("unchecked") // Define T as the method's return type.
            Key<T> metaKey = (Key<T>) meta.baseKey;
            key = metaKey;
            scopeAnnotation = meta.scopeAnnotation;
            dependencies = meta.dependencies;
            messages = meta.messages;
        }
        else {
            Errors errors = new Errors(method);
            point = InjectionPoint.forMethod(method, typeLiteral);
            @SuppressWarnings("unchecked") // Define T as the method's return type.
            TypeLiteral<T> returnType = (TypeLiteral<T>) typeLiteral.getReturnType(method);
            key = getKey(errors, returnType, method, method.getAnnotations());
            scopeAnnotation = Annotations.findScopeAnnotation(errors, method.getAnnotations());
            dependencies = ImmutableSet.copyOf(point.getDependencies());
            messages = ImmutableList.copyOf(errors.getMessages());
        }
        boolean prepareMethodError = false;
        try {
            key =
                    scanner.prepareMethod(binder, annotation, key, point, isStaticModule() ? null : delegate);
        }
        catch (Throwable t) {
            prepareMethodError = true;
            binder.addError(t);
        }

        if (Modifier.isAbstract(method.getModifiers())) {
            checkState(
                    prepareMethodError || key == null,
                    "%s returned a non-null key (%s) for %s. prepareMethod() must return null for abstract"
                            + " methods",
                    scanner,
                    key,
                    method);
            return null;
        }

        if (key == null) { // scanner returned null. Skipping the binding.
            return null;
        }

        for (Message message : messages) {
            binder.addError(message);
        }

        return ProviderMethod.create(
                key,
                method,
                isStaticModule() || Modifier.isStatic(method.getModifiers()) ? null : delegate,
                dependencies,
                scopeAnnotation,
                skipFastClassGeneration,
                annotation);
    }

    <T> Key<T> getKey(Errors errors, TypeLiteral<T> type, Member member, Annotation[] annotations)
    {
        Annotation bindingAnnotation = Annotations.findBindingAnnotation(errors, member, annotations);
        return bindingAnnotation == null ? Key.get(type) : Key.get(type, bindingAnnotation);
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ProviderMethodsModule
                && ((ProviderMethodsModule) o).delegate == delegate
                && ((ProviderMethodsModule) o).scanner.equals(scanner);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(delegate, scanner);
    }

    /**
     * Is it scanning the built-in @Provides* methods.
     */
    public boolean isScanningBuiltInProvidesMethods()
    {
        return scanner == ProvidesMethodScanner.INSTANCE;
    }

    public ModuleAnnotatedMethodScanner getScanner()
    {
        return scanner;
    }
}
