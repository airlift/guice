package com.google.inject.internal;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Key;
import com.google.inject.spi.Dependency;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.inject.internal.InternalMethodHandles.findStaticOrDie;
import static java.lang.invoke.MethodType.methodType;

/**
 * Provides the optional of an {@link com.google.inject.EmptyIfUnbound} dependency whose value is
 * bound, by wrapping the value from the value's factory: an {@code Optional<T>} wraps a {@code T},
 * and an {@code OptionalInt}, {@code OptionalLong} or {@code OptionalDouble} wraps an
 * {@code Integer}, {@code Long} or {@code Double}.
 */
final class EmptyIfUnboundFactory
        extends InternalFactory<Object>
{
    private static final ImmutableMap<Class<?>, Object> EMPTY_VALUES =
            ImmutableMap.of(
                    Optional.class, Optional.empty(),
                    OptionalInt.class, OptionalInt.empty(),
                    OptionalLong.class, OptionalLong.empty(),
                    OptionalDouble.class, OptionalDouble.empty());

    private static final ImmutableMap<Class<?>, Class<?>> PRIMITIVE_VALUE_TYPES =
            ImmutableMap.of(
                    OptionalInt.class, Integer.class,
                    OptionalLong.class, Long.class,
                    OptionalDouble.class, Double.class);

    private static final MethodHandle WRAP =
            findStaticOrDie(
                    EmptyIfUnboundFactory.class, "wrap", methodType(Object.class, Class.class, Object.class));

    private final Class<?> optionalType;
    private final InternalFactory<?> valueFactory;
    private final Dependency<?> valueDependency;

    EmptyIfUnboundFactory(Class<?> optionalType, InternalFactory<?> valueFactory, Dependency<?> valueDependency)
    {
        this.optionalType = optionalType;
        this.valueFactory = valueFactory;
        this.valueDependency = valueDependency;
    }

    /**
     * Returns true if {@code key} is of an optional type, which wraps the value of another key.
     */
    static boolean isOptional(Key<?> key)
    {
        return EMPTY_VALUES.containsKey(key.getTypeLiteral().getRawType());
    }

    /**
     * Returns the key of the value that an optional key wraps, with the same annotation.
     */
    static Key<?> getValueKey(Key<?> optionalKey)
    {
        Class<?> primitiveValueType = PRIMITIVE_VALUE_TYPES.get(optionalKey.getTypeLiteral().getRawType());
        if (primitiveValueType != null) {
            return optionalKey.ofType(primitiveValueType);
        }
        ParameterizedType optionalType = (ParameterizedType) optionalKey.getTypeLiteral().getType();
        return optionalKey.ofType(optionalType.getActualTypeArguments()[0]);
    }

    /**
     * Returns the empty value of an optional type, such as {@code Optional.empty()}.
     */
    static Object empty(Class<?> optionalType)
    {
        return EMPTY_VALUES.get(optionalType);
    }

    static Object wrap(Class<?> optionalType, @Nullable Object value)
    {
        if (value == null) {
            return empty(optionalType);
        }
        if (optionalType == OptionalInt.class) {
            return OptionalInt.of((Integer) value);
        }
        if (optionalType == OptionalLong.class) {
            return OptionalLong.of((Long) value);
        }
        if (optionalType == OptionalDouble.class) {
            return OptionalDouble.of((Double) value);
        }
        return Optional.of(value);
    }

    @Override
    Object get(InternalContext context, Dependency<?> dependency, boolean linked)
            throws InternalProvisionException
    {
        try {
            return wrap(optionalType, valueFactory.get(context, valueDependency, /* linked= */ false));
        }
        catch (InternalProvisionException ipe) {
            throw ipe.addSource(valueDependency.getKey());
        }
    }

    @Override
    MethodHandleResult makeHandle(LinkageContext context, boolean linked)
    {
        // (InternalContext, Dependency) -> Object, passing the value's dependency in place of ours
        MethodHandle value =
                MethodHandles.dropArguments(
                        MethodHandles.insertArguments(
                                valueFactory.getHandle(context, /* linked= */ false), 1, valueDependency),
                        1,
                        Dependency.class);
        return makeCachable(
                InternalMethodHandles.catchInternalProvisionExceptionAndRethrowWithSource(
                        MethodHandles.filterReturnValue(value, MethodHandles.insertArguments(WRAP, 0, optionalType)),
                        valueDependency.getKey()));
    }

    @Override
    public String toString()
    {
        return toStringHelper(EmptyIfUnboundFactory.class)
                .add("optionalType", optionalType.getSimpleName())
                .add("valueDependency", valueDependency)
                .add("valueFactory", valueFactory)
                .toString();
    }
}
