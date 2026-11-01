package com.google.inject.binder;

import java.lang.annotation.Annotation;

/**
 * An {@link AnnotatedBindingBuilder} for a binding whose scope is already set, so it has no scoping
 * methods. See {@link com.google.inject.SingletonBinder}.
 */
public interface AnnotatedSingletonBindingBuilder<T>
        extends LinkedSingletonBindingBuilder<T>
{
    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    LinkedSingletonBindingBuilder<T> annotatedWith(Class<? extends Annotation> annotationType);

    /**
     * See the EDSL examples at {@link com.google.inject.Binder}.
     */
    LinkedSingletonBindingBuilder<T> annotatedWith(Annotation annotation);
}
